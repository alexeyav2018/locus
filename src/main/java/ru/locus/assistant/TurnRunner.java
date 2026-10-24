package ru.locus.assistant;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.locus.Addresses;
import ru.locus.user.UserId;

/**
 * Исполнитель Реплики: процесс Claude Code на одну Реплику в клетке рабочего
 * места учителя под токеном, выпущенным на этого учителя (ADR-0054, ADR-0057;
 * design.md agent-runner, Р4, Р5, Р8, Р9).
 *
 * <p>Реплика идёт на своём виртуальном потоке: он разворачивает рабочее место,
 * выпускает токен, запускает процесс, пишет запрос в stdin и ждёт процесс
 * не дольше предела времени. Вывод читают ещё два виртуальных потока:
 * stdout — события {@link StreamEvent} (сеанс — в Разговор, вызовы — строками
 * хода сразу, итог — для исхода), stderr — хвост для причины неудачи.
 *
 * <p>Исход пишется одним условным обновлением в любом случае — ответ, итог
 * с ошибкой, падение, предел времени, предел ходов, остановка, сбой самого
 * исполнителя; токен отзывается в {@code finally}. Не пишется только при
 * выключении Локуса: тогда строку пометит уборка при следующем старте.
 */
@Component
public class TurnRunner {

    private static final Logger log = LoggerFactory.getLogger(TurnRunner.class);

    /** Сколько ждать процесс после SIGTERM, прежде чем убить его безусловно. */
    static final Duration TERMINATION_GRACE = Duration.ofSeconds(10);

    /** Запас срока токена сверх предела времени: процесс успеет закончить последний вызов. */
    static final Duration TOKEN_MARGIN = Duration.ofMinutes(1);

    /** Сколько последних байт stderr хранится для причины неудачи. */
    static final int STDERR_TAIL = 2048;

    /** Сколько ждать дочитывания вывода после конца процесса. */
    private static final Duration DRAIN = Duration.ofSeconds(5);

    private final AgentProperties properties;
    private final AgentWorkspaces workspaces;
    private final AgentEnvironment environment;
    private final TurnTokens tokens;
    private final AssistantRepository repository;
    private final RunningTurns running;
    private final Clock clock;
    private final Environment application;

    public TurnRunner(AgentProperties properties, AgentWorkspaces workspaces, AgentEnvironment environment,
            TurnTokens tokens, AssistantRepository repository, RunningTurns running, Clock clock,
            Environment application) {
        this.properties = properties;
        this.workspaces = workspaces;
        this.environment = environment;
        this.tokens = tokens;
        this.repository = repository;
        this.running = running;
        this.clock = clock;
        this.application = application;
    }

    /**
     * Что исполнить: Реплика, уже заведённая в «работаю».
     *
     * @param resume сеанс Разговора для {@code --resume}; {@code null} — новый сеанс
     */
    public record Job(UserId owner, AssistantTurnId turn, AssistantConversationId conversation, String prompt,
                      String resume) {
    }

    /** Начать Реплику и сразу вернуться: процесс идёт на своём виртуальном потоке. */
    public void start(Job job) {
        RunningTurns.Running handle = running.register(job.turn());
        Thread.ofVirtual().name("assistant-turn-" + job.turn().value()).start(() -> run(job, handle));
    }

    private void run(Job job, RunningTurns.Running handle) {
        TurnToken token = null;
        Report report = new Report();
        try {
            AgentWorkspaces.Workspace workspace = workspaces.prepare(job.owner());
            token = tokens.issue(job.owner(), properties.timeLimit().plus(TOKEN_MARGIN));
            ProcessBuilder builder = new ProcessBuilder(command(job)).directory(workspace.work().toFile());
            builder.environment().clear();
            builder.environment().putAll(environment.forTurn(workspace.config(), mcpUrl(), token));
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                finish(job, failed("процесс Помощника не запустился: " + e.getMessage()));
                return;
            }
            handle.attach(process);
            finish(job, execute(job, handle, process, report));
        } catch (RuntimeException e) {
            log.error("Реплика {} оборвалась сбоем исполнителя", job.turn().value(), e);
            finish(job, failed("сбой исполнителя Реплики: " + e.getMessage()));
        } finally {
            if (token != null) {
                tokens.revoke(token);
            }
            running.remove(job.turn());
        }
    }

    private TurnOutcome execute(Job job, RunningTurns.Running handle, Process process, Report report) {
        Thread stdout = Thread.ofVirtual().start(() -> readEvents(job, process.getInputStream(), report));
        Thread stderr = Thread.ofVirtual().start(() -> report.stderr.collect(process.getErrorStream()));
        writePrompt(process, job.prompt());
        boolean timedOut;
        try {
            timedOut = !process.waitFor(properties.timeLimit().toMillis(), TimeUnit.MILLISECONDS);
            if (timedOut) {
                process.destroy();
            }
            if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor();
            }
            stdout.join(DRAIN);
            stderr.join(DRAIN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return failed("исполнитель Реплики прерван");
        }
        return outcome(handle.stopped(), timedOut, process.exitValue(), report);
    }

    /** Исход по таблице design.md, Р8. Токены и стоимость — при любом пришедшем итоге. */
    private TurnOutcome outcome(boolean stopped, boolean timedOut, int exitCode, Report report) {
        StreamEvent.Result result = report.result.get();
        if (stopped) {
            return outcome(TurnState.STOPPED, null, null, result);
        }
        if (timedOut) {
            return outcome(TurnState.TIMED_OUT, null,
                    "предел времени Реплики " + properties.timeLimit().toSeconds() + " с", result);
        }
        if (result == null) {
            String tail = report.stderr.tail();
            return outcome(TurnState.FAILED, null, "процесс Помощника вышел с кодом " + exitCode
                    + " без итога" + (tail.isBlank() ? "" : ": " + tail), null);
        }
        if (result.stepLimit()) {
            return outcome(TurnState.STEP_LIMIT, null,
                    "предел ходов Реплики " + properties.maxTurns(), result);
        }
        if (result.error()) {
            return outcome(TurnState.FAILED, null,
                    result.text().isBlank() ? "итог Помощника: " + result.kind() : result.text(), result);
        }
        return outcome(TurnState.DONE, result.text(), null, result);
    }

    private TurnOutcome outcome(TurnState state, String answer, String cause, StreamEvent.Result result) {
        return new TurnOutcome(state, answer, cause,
                result == null ? null : result.inputTokens(),
                result == null ? null : result.outputTokens(),
                result == null ? null : result.costUsd(),
                clock.instant());
    }

    private TurnOutcome failed(String cause) {
        return outcome(TurnState.FAILED, null, cause, null);
    }

    private void finish(Job job, TurnOutcome outcome) {
        if (running.shuttingDown()) {
            return;
        }
        repository.finishTurn(job.owner(), job.turn(), outcome);
    }

    /** Команда и клетка (Р4): только инструменты MCP, только {@code mcp__locus__*}. */
    private List<String> command(Job job) {
        List<String> command = new ArrayList<>(List.of(
                properties.executable(), "-p", "--output-format", "stream-json", "--verbose",
                "--max-turns", Integer.toString(properties.maxTurns()),
                "--tools", "",
                "--allowedTools", "mcp__locus__*"));
        if (job.resume() != null) {
            command.add("--resume");
            command.add(job.resume());
        }
        return command;
    }

    /** Адрес из настроек либо сервер этого же приложения на его настоящем порту. */
    private String mcpUrl() {
        if (!properties.mcpUrl().isBlank()) {
            return properties.mcpUrl();
        }
        String port = application.getProperty("local.server.port", application.getProperty("server.port", "8080"));
        return "http://127.0.0.1:" + port + Addresses.MCP;
    }

    /**
     * Запрос — на стандартный ввод, а не аргументом: аргумент виден в списке
     * процессов любому пользователю стенда и ограничен длиной командной строки.
     * Процесс, вышедший не дочитав, — не повод для отказа: исход скажет код выхода.
     */
    private static void writePrompt(Process process, String prompt) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // процесс закрыл ввод — исход определит его выход
        }
    }

    private void readEvents(Job job, InputStream stdout, Report report) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                try {
                    record(job, StreamEvent.parse(line), report);
                } catch (RuntimeException e) {
                    // вывод дочитывается и дальше: недочитанный процесс встал бы на полном канале
                    log.error("Реплика {}: событие вывода не записано", job.turn().value(), e);
                }
            }
        } catch (IOException ignored) {
            // процесс убит — дочитывать нечего
        }
    }

    /** Сеанс — в Разговор, вызовы — строками хода сразу, итог — для исхода. */
    private void record(Job job, StreamEvent event, Report report) {
        switch (event) {
            case StreamEvent.Init init -> repository.recordSession(job.owner(), job.conversation(), init.sessionId());
            case StreamEvent.ToolCalls calls -> calls.calls().forEach(call ->
                    repository.addStep(job.owner(), job.turn(), call.name(), call.arguments()));
            case StreamEvent.Result result -> report.result.set(result);
            case StreamEvent.Other other -> {
            }
        }
    }

    /** То, что потоки вывода собрали для исхода. */
    private static final class Report {
        private final AtomicReference<StreamEvent.Result> result = new AtomicReference<>();
        private final Tail stderr = new Tail();
    }

    /** Последние {@link #STDERR_TAIL} байт потока. */
    private static final class Tail {

        private final byte[] buffer = new byte[STDERR_TAIL];
        private int length;

        void collect(InputStream in) {
            byte[] chunk = new byte[1024];
            try (in) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    append(chunk, read);
                }
            } catch (IOException ignored) {
                // процесс убит — хвост тот, что успел прийти
            }
        }

        private synchronized void append(byte[] chunk, int read) {
            int keep = Math.min(length, STDERR_TAIL - Math.min(read, STDERR_TAIL));
            System.arraycopy(buffer, length - keep, buffer, 0, keep);
            int take = Math.min(read, STDERR_TAIL);
            System.arraycopy(chunk, read - take, buffer, keep, take);
            length = keep + take;
        }

        synchronized String tail() {
            return new String(buffer, 0, length, StandardCharsets.UTF_8).strip();
        }
    }
}
