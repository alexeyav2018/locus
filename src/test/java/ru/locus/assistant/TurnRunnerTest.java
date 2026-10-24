package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.util.FileSystemUtils;
import ru.locus.Addresses;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Исполнитель Реплики на тест-двойнике команды Claude Code (спека
 * {@code assistant-tools}; design.md agent-runner, Р3–Р9). Двойник печатает
 * строки, снятые с настоящего вывода, по первому слову запроса и записывает,
 * с чем его запустили, в {@code claude-double.txt} рядом с рабочим местом.
 * Предел времени в тестах — 3 с ({@link IntegrationTest#AGENT_TIME_LIMIT}).
 */
class TurnRunnerTest extends IntegrationTest {

    /** Сколько ждать конца Реплики: предел времени, запас на SIGTERM и на медленную машину. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private static final Path WORKSPACES = Path.of("target/test-agent-workspaces");

    @LocalServerPort
    private int port;

    @Autowired
    private AssistantService service;
    @Autowired
    private AssistantRepository repository;
    @Autowired
    private TurnTokens tokens;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private AgentStartup startup;

    /**
     * Записи двойника прошлого прогона: база у каждого прогона чистая,
     * идентификаторы учителей повторяются, а папка в {@code target/} остаётся.
     */
    @BeforeAll
    static void forgetPreviousRuns() throws IOException {
        FileSystemUtils.deleteRecursively(WORKSPACES);
    }

    @AfterEach
    void logout() {
        LoggedIn.nobody();
    }

    /** Сценарий «Реплика с ответом». */
    @Test
    void answeredTurnIsDoneWithAnswerTokensAndCost() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);

        AssistantTurn started = service.startTurn(Optional.empty(), "ответ на вопрос");
        assertThat(started.state()).isEqualTo(TurnState.RUNNING);

        AssistantTurn done = finished(started);
        assertThat(done.state()).isEqualTo(TurnState.DONE);
        assertThat(done.answer()).isNotBlank();
        assertThat(done.cause()).isNull();
        assertThat(done.inputTokens()).isPositive();
        assertThat(done.outputTokens()).isPositive();
        assertThat(done.costUsd()).isPositive();
        assertThat(done.finishedAt()).isNotNull();
        assertThat(service.steps(done.id())).isEmpty();
    }

    /** Сценарий «Реплика с вызовами инструментов». */
    @Test
    void toolCallsBecomeStepsInOrder() {
        LoggedIn.as(accounts.settled(Role.TEACHER));

        AssistantTurn done = finished(service.startTurn(Optional.empty(), "вызовы"));

        assertThat(done.state()).isEqualTo(TurnState.DONE);
        List<TurnStep> steps = service.steps(done.id());
        assertThat(steps).extracting(TurnStep::position).containsExactly(1, 2);
        assertThat(steps).extracting(TurnStep::toolName)
                .containsExactly("mcp__locus__list_topics", "mcp__locus__list_methods");
    }

    /** Сценарий «Зависание»: убит в пределе с запасом на остановку. */
    @Test
    void hangingProcessIsKilledAtTheTimeLimit() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        Instant start = Instant.now();

        AssistantTurn timedOut = finished(service.startTurn(Optional.empty(), "зависание"));

        Duration took = Duration.between(start, Instant.now());
        assertThat(timedOut.state()).isEqualTo(TurnState.TIMED_OUT);
        assertThat(timedOut.cause()).contains("3 с");
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofSeconds(3))
                .isLessThan(Duration.ofSeconds(3).plus(TurnRunner.TERMINATION_GRACE));
    }

    /** Сценарий «Падение»: причина называет код выхода и вывод ошибок. */
    @Test
    void crashIsFailedWithExitCodeAndStderr() {
        LoggedIn.as(accounts.settled(Role.TEACHER));

        AssistantTurn failed = finished(service.startTurn(Optional.empty(), "падение"));

        assertThat(failed.state()).isEqualTo(TurnState.FAILED);
        assertThat(failed.cause()).contains("кодом 3").contains("двойник: падение по запросу");
        assertThat(failed.costUsd()).isNull();
    }

    /** Сценарий «Предел ходов»: токены и стоимость записаны. */
    @Test
    void stepLimitIsItsOwnStateWithTokens() {
        LoggedIn.as(accounts.settled(Role.TEACHER));

        AssistantTurn limited = finished(service.startTurn(Optional.empty(), "шаги"));

        assertThat(limited.state()).isEqualTo(TurnState.STEP_LIMIT);
        assertThat(limited.cause()).contains("10");
        assertThat(limited.inputTokens()).isPositive();
        assertThat(limited.costUsd()).isPositive();
        assertThat(service.steps(limited.id())).hasSize(1);
    }

    /** Сценарии «Остановка» и «Вторая Реплика при идущей». */
    @Test
    void secondTurnIsRefusedWhileFirstRunsAndFirstCanBeStopped() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        AssistantTurn first = service.startTurn(Optional.empty(), "зависание");

        assertThatThrownBy(() -> service.startTurn(Optional.empty(), "ответ"))
                .isInstanceOf(AssistantBusyException.class)
                .hasMessageContaining("Помощник ещё отвечает");
        assertThatThrownBy(() -> service.startTurn(Optional.of(first.conversation()), "ответ"))
                .isInstanceOf(AssistantBusyException.class);
        assertThat(service.turn(first.id()).state()).isEqualTo(TurnState.RUNNING);

        Instant stopAt = Instant.now();
        service.stop(first.id());
        AssistantTurn stopped = finished(first);

        assertThat(stopped.state()).isEqualTo(TurnState.STOPPED);
        assertThat(Duration.between(stopAt, Instant.now())).isLessThan(Duration.ofSeconds(3));
        assertThat(finished(service.startTurn(Optional.empty(), "ответ")).state()).isEqualTo(TurnState.DONE);
    }

    /** Сценарии «Два учителя одновременно», «Чужая Реплика», «Продолжение чужого Разговора». */
    @Test
    void anotherTeachersTurnRunsAndForeignOnesAreInvisible() {
        TestAccounts.Account alice = accounts.settled(Role.TEACHER);
        TestAccounts.Account bob = accounts.settled(Role.TEACHER);
        LoggedIn.as(alice);
        AssistantTurn alices = service.startTurn(Optional.empty(), "зависание");

        LoggedIn.as(bob);
        AssistantTurn bobs = finished(service.startTurn(Optional.empty(), "ответ"));
        assertThat(bobs.state()).isEqualTo(TurnState.DONE);
        assertThatThrownBy(() -> service.turn(alices.id())).isInstanceOf(AssistantTurnNotFoundException.class);
        assertThatThrownBy(() -> service.steps(alices.id())).isInstanceOf(AssistantTurnNotFoundException.class);
        assertThatThrownBy(() -> service.stop(alices.id())).isInstanceOf(AssistantTurnNotFoundException.class);
        assertThatThrownBy(() -> service.startTurn(Optional.of(alices.conversation()), "ответ"))
                .isInstanceOf(AssistantConversationNotFoundException.class);

        LoggedIn.as(alice);
        assertThat(service.turn(alices.id()).state()).isEqualTo(TurnState.RUNNING);
        service.stop(alices.id());
        assertThat(finished(alices).state()).isEqualTo(TurnState.STOPPED);
    }

    /** Сценарии «Вторая Реплика в Разговоре» и «Реплика нового Разговора». */
    @Test
    void secondTurnOfAConversationResumesItsSession() throws IOException {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);

        AssistantTurn first = finished(service.startTurn(Optional.empty(), "ответ"));
        assertThat(arguments(teacher)).doesNotContain("--resume");
        String session = repository.findConversation(teacher.id(), first.conversation()).orElseThrow().session();
        assertThat(session).isNotBlank();

        AssistantTurn second = finished(service.startTurn(Optional.of(first.conversation()), "ответ ещё раз"));

        assertThat(second.conversation()).isEqualTo(first.conversation());
        List<String> arguments = arguments(teacher);
        assertThat(arguments.get(arguments.indexOf("--resume") + 1)).isEqualTo(session);

        finished(service.startTurn(Optional.empty(), "ответ в новом разговоре"));
        assertThat(arguments(teacher)).doesNotContain("--resume");
    }

    /** Команда, клетка и запрос на стандартном вводе (design.md, Р4). */
    @Test
    void processGetsTheCageFlagsAndThePromptOnStdin() throws IOException {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);

        finished(service.startTurn(Optional.empty(), "ответ: что в Теме «Дроби»?"));

        List<String> arguments = arguments(teacher);
        assertThat(arguments).containsSubsequence("-p", "--output-format", "stream-json", "--verbose",
                "--max-turns", "10", "--tools", "", "--allowedTools", "mcp__locus__*");
        assertThat(arguments).noneMatch(argument -> argument.contains("Дроби"));
        assertThat(record(teacher)).endsWith("prompt:\nответ: что в Теме «Дроби»?\n");
        assertThat(value(teacher, "mcp")).isEqualTo("http://127.0.0.1:" + port + Addresses.MCP);
        assertThat(value(teacher, "claudeai")).as("коннекторы claude.ai выключены").isEqualTo("false");
    }

    /** Сценарий «Токен живёт ровно Реплику». */
    @Test
    void tokenIsRefusedOnceTheTurnIsOver() throws Exception {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        AssistantTurn running = service.startTurn(Optional.empty(), "зависание");
        String token = awaitValue(teacher, "token");
        assertThat(tokens.resolve(token)).contains(teacher.id());

        service.stop(running.id());
        finished(running);

        assertThat(tokens.resolve(token)).isEmpty();
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + Addresses.MCP))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,"
                                + "\"method\":\"tools/list\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    /** Сценарий «Процесс без лишнего окружения» (design.md, Р5). */
    @Test
    void processEnvironmentIsTheWhiteList() throws IOException {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);

        finished(service.startTurn(Optional.empty(), "ответ"));

        List<String> names = record(teacher).lines()
                .filter(line -> line.startsWith("env="))
                .map(line -> line.substring("env=".length()))
                .toList();
        assertThat(names).contains(AgentEnvironment.API_KEY, AgentEnvironment.CONFIG_DIR,
                AgentEnvironment.MCP_URL, AgentEnvironment.TURN_TOKEN, AgentEnvironment.CLAUDEAI_SERVERS, "PATH");
        assertThat(names).doesNotContain(TestAgentEnvironment.SECRET, "CLAUDECODE");
    }

    /** Сценарий «Рабочие места учителей разные». */
    @Test
    void twoTeachersWorkInDifferentWorkspaces() throws IOException {
        TestAccounts.Account alice = accounts.settled(Role.TEACHER);
        TestAccounts.Account bob = accounts.settled(Role.TEACHER);
        LoggedIn.as(alice);
        finished(service.startTurn(Optional.empty(), "ответ"));
        LoggedIn.as(bob);
        finished(service.startTurn(Optional.empty(), "ответ"));

        assertThat(value(alice, "cwd")).isNotEqualTo(value(bob, "cwd"));
        assertThat(value(alice, "config")).isNotEqualTo(value(bob, "config"));
        assertThat(Path.of(value(alice, "cwd"))).isEqualTo(home(alice).resolve("work").toAbsolutePath().normalize());
    }

    /** Сценарий «Перезапуск посреди Реплики». */
    @Test
    void runningTurnBecomesInterruptedOnStartup() throws Exception {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        AssistantConversationId conversation = repository.createConversation(teacher.id(), Instant.now());
        AssistantTurnId orphan = repository.startTurn(teacher.id(), conversation, "без процесса", Instant.now());

        startup.run(null);

        AssistantTurn interrupted = repository.findTurn(teacher.id(), orphan).orElseThrow();
        assertThat(interrupted.state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(interrupted.cause()).isEqualTo(AgentStartup.INTERRUPTED);
        LoggedIn.as(teacher);
        assertThat(finished(service.startTurn(Optional.of(conversation), "ответ")).state())
                .isEqualTo(TurnState.DONE);
    }

    /** Сценарий «Пустой запрос». */
    @Test
    void emptyAndOverlongPromptsAreRefused() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);

        assertThatThrownBy(() -> service.startTurn(Optional.empty(), "  \n "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("пуст");
        assertThatThrownBy(() -> service.startTurn(Optional.empty(), "ответ " + "я".repeat(20_000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20 000");
        assertThat(Files.exists(home(teacher))).as("процесс не запускался").isFalse();
    }

    private AssistantTurn finished(AssistantTurn turn) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            AssistantTurn current = repository.findTurn(turn.owner(), turn.id()).orElseThrow();
            if (!current.state().running()) {
                return current;
            }
            pause();
        }
        throw new AssertionError("Реплика " + turn.id().value() + " не завершилась за " + PATIENCE);
    }

    private static Path home(TestAccounts.Account teacher) {
        return WORKSPACES.resolve(Long.toString(teacher.id().value()));
    }

    private static String record(TestAccounts.Account teacher) throws IOException {
        return Files.readString(home(teacher).resolve("claude-double.txt"));
    }

    private static List<String> arguments(TestAccounts.Account teacher) throws IOException {
        return record(teacher).lines()
                .takeWhile(line -> line.startsWith("arg="))
                .map(line -> line.substring("arg=".length()))
                .toList();
    }

    private static String value(TestAccounts.Account teacher, String name) throws IOException {
        return record(teacher).lines()
                .filter(line -> line.startsWith(name + "="))
                .map(line -> line.substring(name.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("двойник не записал " + name));
    }

    /** Значение из записи двойника, когда он её сделает: процесс идёт параллельно тесту. */
    private static String awaitValue(TestAccounts.Account teacher, String name) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            try {
                String found = value(teacher, name);
                if (!found.isBlank()) {
                    return found;
                }
            } catch (IOException | AssertionError notYet) {
                // двойник ещё не записал
            }
            pause();
        }
        throw new AssertionError("двойник не записал " + name + " за " + PATIENCE);
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
