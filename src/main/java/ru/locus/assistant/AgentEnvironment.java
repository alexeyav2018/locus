package ru.locus.assistant;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Окружение Локуса глазами Помощника: есть ли ключ и что из окружения
 * достаётся процессу Claude Code (design.md agent-runner, Р5, Р6, Р10).
 *
 * <p>Процесс получает не окружение Локуса целиком, а белый список: в окружении
 * Локуса лежат секреты хранилища файлов и базы (ADR-0051), процессу в клетке
 * они не нужны. Заодно туда не попадает {@code CLAUDECODE}, с которым
 * Claude Code отклоняет вложенный запуск.
 *
 * <p>Окружение — бин, а не {@code System.getenv()} по месту: тесты подставляют
 * ключ и секрет, не трогая окружение сборки.
 */
public class AgentEnvironment {

    /** Ключ API владельца. */
    public static final String API_KEY = "ANTHROPIC_API_KEY";

    /** Токен подписки владельца ({@code claude setup-token}). */
    public static final String OAUTH_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN";

    /** Адрес сервера MCP Локуса — его подставляет {@code .mcp.json} рабочего места. */
    public static final String MCP_URL = "LOCUS_MCP_URL";

    /** Токен Реплики — его подставляет {@code .mcp.json} рабочего места. */
    public static final String TURN_TOKEN = "LOCUS_TURN_TOKEN";

    /** Каталог настроек и сеансов Claude Code учителя. */
    public static final String CONFIG_DIR = "CLAUDE_CONFIG_DIR";

    /**
     * Выключатель коннекторов claude.ai. Со входом по подписке Claude Code
     * подключает серверы MCP учётной записи claude.ai мимо {@code .mcp.json}
     * и каталога настроек — в клетке их быть не должно (сверено живым
     * запуском, design.md agent-runner, Р4).
     */
    public static final String CLAUDEAI_SERVERS = "ENABLE_CLAUDEAI_MCP_SERVERS";

    /** Что процесс берёт из окружения Локуса как есть, если оно там задано. */
    private static final List<String> SYSTEM = List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR");

    private final Map<String, String> variables;

    public AgentEnvironment(Map<String, String> variables) {
        this.variables = Map.copyOf(variables);
    }

    /** Есть ли в окружении ключ — ключ API или токен подписки (Р6). */
    public boolean hasKey() {
        return present(API_KEY) || present(OAUTH_TOKEN);
    }

    /** Окружение команды без Реплики — для проверки версии. */
    public Map<String, String> system() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : SYSTEM) {
            if (present(name)) {
                result.put(name, variables.get(name));
            }
        }
        return result;
    }

    /**
     * Окружение процесса Реплики: системное, ключ, каталог настроек, адрес
     * и токен; коннекторы claude.ai выключены.
     */
    public Map<String, String> forTurn(Path config, String mcpUrl, TurnToken token) {
        Map<String, String> result = system();
        for (String key : List.of(API_KEY, OAUTH_TOKEN)) {
            if (present(key)) {
                result.put(key, variables.get(key));
            }
        }
        result.put(CONFIG_DIR, config.toString());
        result.put(CLAUDEAI_SERVERS, "false");
        result.put(MCP_URL, mcpUrl);
        result.put(TURN_TOKEN, token.value());
        return result;
    }

    private boolean present(String name) {
        String value = variables.get(name);
        return value != null && !value.isBlank();
    }
}
