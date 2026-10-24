package ru.locus.assistant;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки исполнителя Реплики Помощника (ADR-0054; design.md agent-runner, Р13).
 *
 * @param command    путь к команде Claude Code
 * @param version    закреплённая версия: команда другой версии делает Помощника
 *                   недоступным, а не тихо ломает разбор её вывода (Р10)
 * @param workspaces корень рабочих мест учителей (Р7)
 * @param mcpUrl     адрес сервера MCP Локуса для процесса; пустой — сервер
 *                   этого же приложения на его настоящем порту
 * @param timeLimit  предел времени Реплики
 * @param maxTurns   предел ходов Реплики
 */
@ConfigurationProperties(prefix = "locus.agent")
public record AgentProperties(
        @DefaultValue("claude") String command,
        @DefaultValue("2.1.309") String version,
        @DefaultValue("./.locus-agent") Path workspaces,
        @DefaultValue("") String mcpUrl,
        @DefaultValue("180s") Duration timeLimit,
        @DefaultValue("10") int maxTurns) {

    /**
     * Команда к запуску: путь с каталогом — абсолютным от каталога Локуса,
     * имя без каталога — как есть, его найдёт {@code PATH}. Относительный
     * путь иначе искался бы от рабочего места процесса, а не от Локуса.
     */
    public String executable() {
        return command.contains("/") ? Path.of(command).toAbsolutePath().normalize().toString() : command;
    }
}
