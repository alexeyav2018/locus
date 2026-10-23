package ru.locus.assistant;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.locus.Addresses;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сервер MCP внутри приложения (ADR-0054; design.md, Р1 и Р2).
 *
 * <p>Транспорт — Streamable HTTP без сессий
 * ({@link HttpServletStatelessServerTransport}): каждый вызов
 * самодостаточен, между вызовами сервер ничего не держит, а уведомлений
 * сервера Помощнику не нужно. Сервлет стоит на {@link Addresses#MCP}
 * того же порта, что и страницы; замок — цепочка
 * {@link McpSecurityConfiguration} перед сервлетом.
 *
 * <p>Инструменты — ровно реестр {@link ToolRegistry}, каждый через
 * {@link ToolAdapter}. Проверка аргументов по схеме на стороне SDK
 * выключена: разбор аргументов — дело {@link Arguments}, и отказ тогда
 * один, по-русски и называющий аргумент, а не два разных на один случай.
 *
 * <p>Транспорт намеренно не объявлен бином: всякий бин-сервлет Spring Boot
 * регистрирует сам, по имени бина, и сервер отозвался бы по двум адресам.
 * Сервер собирается здесь же и живёт внутри транспорта его обработчиком;
 * остановка приложения останавливает его через {@code destroy()} сервлета.
 */
@Configuration
public class McpServerConfiguration {

    /** Имя, которым сервер представляется клиенту. */
    static final String SERVER_NAME = "locus";

    /** Версия для клиента, когда сборка без {@code build-info} — запуск из исходников. */
    static final String UNVERSIONED = "dev";

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            ToolRegistry registry, JsonMapper jsonMapper, ObjectProvider<BuildProperties> build) {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(jsonMapper);
        ToolAdapter adapter = new ToolAdapter(jsonMapper);
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .jsonMapper(mapper)
                .messageEndpoint(Addresses.MCP)
                .contextExtractor(adapter::carry)
                .build();
        McpServer.sync(transport)
                .serverInfo(SERVER_NAME, version(build))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonMapper(mapper)
                .validateToolInputs(false)
                .tools(registry.tools().stream().map(adapter::adapt).toList())
                .build();
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, Addresses.MCP);
        registration.setName("mcp");
        return registration;
    }

    private static String version(ObjectProvider<BuildProperties> build) {
        BuildProperties built = build.getIfAvailable();
        return built == null || built.getVersion() == null ? UNVERSIONED : built.getVersion();
    }
}
