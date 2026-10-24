package ru.locus.assistant;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Доступен ли Помощник, и если нет — почему (спека {@code assistant-tools},
 * «Помощник без команды недоступен с причиной»; design.md agent-runner, Р6, Р10).
 *
 * <p>Проверка одна, при старте: команда есть и отвечает на {@code --version}
 * за {@link #VERSION_TIMEOUT}, первое слово ответа — закреплённая версия,
 * в окружении есть ключ API или токен подписки. Повторно не проверяется:
 * Claude Code ставится с остановкой стенда (ADR-0024). При любом итоге Локус
 * поднимается — недоступен только Помощник.
 *
 * @param available доступен ли Помощник
 * @param version   версия команды, если она ответила
 * @param cause     причина недоступности; у доступного — пусто
 */
public record AssistantAvailability(boolean available, String version, String cause) {

    /** Сколько ждать ответа команды на {@code --version}. */
    static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);

    /** До проверки при старте Помощник недоступен. */
    public static AssistantAvailability notChecked() {
        return new AssistantAvailability(false, null, "доступность Помощника ещё не проверена");
    }

    /**
     * Проверить команду, её версию и ключ.
     *
     * @param executable команда, как её запустит исполнитель ({@link AgentProperties#executable()})
     * @param pinned     закреплённая версия
     */
    public static AssistantAvailability check(String executable, String pinned, AgentEnvironment environment) {
        String answer;
        try {
            answer = versionOf(executable, environment);
        } catch (UnavailableCommand e) {
            return new AssistantAvailability(false, null, e.getMessage());
        }
        String version = answer.strip().split("\\s+", 2)[0];
        if (!version.equals(pinned)) {
            return new AssistantAvailability(false, version,
                    "версия Claude Code " + version + ", а закреплена " + pinned);
        }
        if (!environment.hasKey()) {
            return new AssistantAvailability(false, version, "в окружении Локуса нет ключа — ни "
                    + AgentEnvironment.API_KEY + ", ни " + AgentEnvironment.OAUTH_TOKEN);
        }
        return new AssistantAvailability(true, version, null);
    }

    private static String versionOf(String executable, AgentEnvironment environment) {
        if (!found(executable, environment.system().get("PATH"))) {
            throw new UnavailableCommand("команда Claude Code не найдена: " + executable);
        }
        ProcessBuilder builder = new ProcessBuilder(List.of(executable, "--version")).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment.system());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UnavailableCommand("команда Claude Code не запускается: " + e.getMessage());
        }
        try {
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), output));
            if (!process.waitFor(VERSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new UnavailableCommand("команда Claude Code не ответила на --version за "
                        + VERSION_TIMEOUT.toSeconds() + " с: " + executable);
            }
            reader.join(VERSION_TIMEOUT.toMillis());
            String text = output.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new UnavailableCommand("команда Claude Code ответила на --version кодом "
                        + process.exitValue() + ": " + text.strip());
            }
            if (text.isBlank()) {
                throw new UnavailableCommand("команда Claude Code не назвала версию: " + executable);
            }
            return text;
        } catch (IOException e) {
            process.destroyForcibly();
            throw new UnavailableCommand("команда Claude Code не ответила на --version: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new UnavailableCommand("проверка версии Claude Code прервана");
        }
    }

    /** Путь с каталогом — есть ли файл; имя без каталога — есть ли оно в одном из каталогов {@code PATH}. */
    private static boolean found(String executable, String path) {
        if (executable.contains("/")) {
            return Files.isRegularFile(Path.of(executable));
        }
        if (path == null) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (!directory.isEmpty() && Files.isRegularFile(Path.of(directory, executable))) {
                return true;
            }
        }
        return false;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // процесс убит по пределу — недочитанный вывод уже не нужен
        }
    }

    private static final class UnavailableCommand extends RuntimeException {
        UnavailableCommand(String message) {
            super(message);
        }
    }
}
