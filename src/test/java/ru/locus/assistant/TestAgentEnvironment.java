package ru.locus.assistant;

import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Окружение Локуса в тестах: окружение сборки, ключ API и секрет хранилища
 * файлов. Ключ делает Помощника с двойником доступным, секрет проверяет белый
 * список окружения процесса (design.md agent-runner, Р5). Подменяет бин
 * {@link AgentEnvironment} на всём тестовом контексте, как {@code TestClock}
 * часы, — второго контекста Spring не поднимает.
 */
@Component
@Primary
public class TestAgentEnvironment extends AgentEnvironment {

    /** Секрет, заданный Локусу и не нужный процессу Помощника. */
    public static final String SECRET = "LOCUS_FILE_OBJECT_SECRET_KEY";

    public TestAgentEnvironment() {
        super(variables());
    }

    private static Map<String, String> variables() {
        Map<String, String> variables = new HashMap<>(System.getenv());
        variables.put(API_KEY, "ключ-для-двойника");
        variables.put(SECRET, "секрет-хранилища");
        return variables;
    }
}
