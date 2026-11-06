package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Спека {@code assistant-turns}, «Помощник без команды недоступен с причиной»;
 * design.md assistant-availability-recheck, Р1–Р4. Без Spring: команда —
 * свой сценарий во временной папке, каждый запуск оставляет строку в файле.
 */
class AvailabilityCheckTest {

    private static final String PINNED = "2.1.309";

    @TempDir
    Path folder;

    /** Сценарий «Страница возвращает Помощника»: без перезапуска, тем же бином. */
    @Test
    void repairedCommandBringsTheAssistantBack() throws IOException {
        Path command = folder.resolve("claude");
        AvailabilityCheck check = check(command);
        command(command, "echo сломана >&2; exit 1");

        AssistantAvailability failed = check.current();
        assertThat(failed.available()).isFalse();
        assertThat(failed.cause()).contains("кодом 1");

        command(command, "echo " + PINNED);

        assertThat(check.current().available()).isTrue();
        assertThat(check.last().available()).isTrue();
    }

    /** Сценарий «Сбой продолжается»: каждая неудача — новая проверка. */
    @Test
    void unavailableAssistantIsCheckedOnEveryCall() throws IOException {
        Path command = folder.resolve("claude");
        AvailabilityCheck check = check(command);
        command(command, "echo run >> " + launches() + "; exit 1");

        check.current();
        check.current();

        assertThat(launchCount()).isEqualTo(2);
    }

    /** Сценарий «Доступный не проверяется». */
    @Test
    void availableAssistantIsNotCheckedAgain() throws IOException {
        Path command = folder.resolve("claude");
        AvailabilityCheck check = check(command);
        command(command, "echo run >> " + launches() + "; echo " + PINNED);

        check.current();
        check.current();
        check.current();

        assertThat(launchCount()).isEqualTo(1);
    }

    @Test
    void lastDoesNotRunTheCommand() throws IOException {
        Path command = folder.resolve("claude");
        AvailabilityCheck check = check(command);
        command(command, "echo run >> " + launches() + "; echo " + PINNED);

        assertThat(check.last().available()).isFalse();

        assertThat(launchCount()).isZero();
    }

    /** Сценарий «Проверка уже идёт». */
    @Test
    void secondCallDoesNotWaitForTheRunningCheck() throws Exception {
        Path command = folder.resolve("claude");
        AvailabilityCheck check = check(command);
        command(command, "echo run >> " + launches() + "; sleep 2; echo " + PINNED);

        CompletableFuture<AssistantAvailability> first = CompletableFuture.supplyAsync(check::current);
        waitForTheFirstLaunch();

        assertThat(check.current().available()).isFalse();
        assertThat(first.get().available()).isTrue();
        assertThat(launchCount()).isEqualTo(1);
    }

    private void waitForTheFirstLaunch() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (launchCount() == 0) {
            assertThat(System.nanoTime()).as("команда не запустилась").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private Path launches() {
        return folder.resolve("launches");
    }

    private int launchCount() throws IOException {
        return Files.exists(launches()) ? Files.readAllLines(launches()).size() : 0;
    }

    private static AvailabilityCheck check(Path command) {
        AgentProperties properties = new AgentProperties(command.toString(), PINNED, Path.of("unused"), "",
                Duration.ofSeconds(1), 1);
        Map<String, String> variables = new HashMap<>(System.getenv());
        variables.put(AgentEnvironment.API_KEY, "ключ");
        return new AvailabilityCheck(properties, new AgentEnvironment(variables));
    }

    /** Пишет во временный файл и подменяет разом: запись поверх исполняемого даёт «Text file busy». */
    private static void command(Path path, String body) throws IOException {
        Path written = path.resolveSibling(path.getFileName() + ".new");
        Files.writeString(written, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.move(written, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
