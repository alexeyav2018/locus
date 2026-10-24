package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Спека {@code assistant-tools}, «Помощник без команды недоступен
 * с причиной»; design.md agent-runner, Р6, Р10. Чистая проверка, без Spring:
 * команда — тест-двойник либо свой сценарий во временной папке.
 */
class AssistantAvailabilityTest {

    private static final String DOUBLE = Path.of("src/test/resources/assistant/claude-double")
            .toAbsolutePath().toString();
    private static final String PINNED = "2.1.309";

    private static final AgentEnvironment WITH_API_KEY = environment(Map.of(AgentEnvironment.API_KEY, "ключ"));

    @TempDir
    Path folder;

    /** Сценарий «Нет команды». */
    @Test
    void missingCommandIsNamed() {
        String missing = folder.resolve("no-such-claude").toString();

        AssistantAvailability availability = AssistantAvailability.check(missing, PINNED, WITH_API_KEY);

        assertThat(availability.available()).isFalse();
        assertThat(availability.cause()).contains("команда Claude Code не найдена").contains(missing);
    }

    /** Сценарий «Чужая версия»: причина называет обе. */
    @Test
    void foreignVersionNamesBoth() throws Exception {
        Path other = folder.resolve("claude");
        Files.writeString(other, "#!/usr/bin/env bash\necho '2.0.1 (Claude Code)'\n");
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));

        AssistantAvailability availability = AssistantAvailability.check(other.toString(), PINNED, WITH_API_KEY);

        assertThat(availability.available()).isFalse();
        assertThat(availability.version()).isEqualTo("2.0.1");
        assertThat(availability.cause()).contains("2.0.1").contains(PINNED);
    }

    /** Сценарий «Нет ключа»: причина называет обе переменные. */
    @Test
    void missingKeyNamesBothVariables() {
        AssistantAvailability availability = AssistantAvailability.check(DOUBLE, PINNED, environment(Map.of()));

        assertThat(availability.available()).isFalse();
        assertThat(availability.cause())
                .contains(AgentEnvironment.API_KEY)
                .contains(AgentEnvironment.OAUTH_TOKEN);
    }

    /** Сценарий «Всё на месте» — и с ключом API, и с токеном подписки (Р6). */
    @Test
    void pinnedVersionWithEitherKeyIsAvailable() {
        AssistantAvailability byApiKey = AssistantAvailability.check(DOUBLE, PINNED, WITH_API_KEY);
        AssistantAvailability bySubscription = AssistantAvailability.check(DOUBLE, PINNED,
                environment(Map.of(AgentEnvironment.OAUTH_TOKEN, "токен")));

        assertThat(byApiKey.available()).isTrue();
        assertThat(byApiKey.version()).isEqualTo(PINNED);
        assertThat(byApiKey.cause()).isNull();
        assertThat(bySubscription.available()).isTrue();
    }

    @Test
    void blankKeyIsNoKey() {
        AssistantAvailability availability = AssistantAvailability.check(DOUBLE, PINNED,
                environment(Map.of(AgentEnvironment.API_KEY, " ")));

        assertThat(availability.available()).isFalse();
    }

    /** Окружение с ключом и путём поиска команд: без PATH сценарию двойника не найти bash. */
    private static AgentEnvironment environment(Map<String, String> key) {
        Map<String, String> variables = new HashMap<>(key);
        variables.put("PATH", System.getenv("PATH"));
        return new AgentEnvironment(variables);
    }
}
