package ru.locus.assistant;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.net.http.HttpRequest;
import java.time.Duration;
import ru.locus.Addresses;
import tools.jackson.databind.json.JsonMapper;

/**
 * Клиент того же SDK — тест-двойник Помощника: по HTTP к поднятому
 * приложению, токен Реплики в заголовке каждого запроса (ADR-0057).
 * Так сервер увидит и настоящий Claude Code, когда его подключит
 * {@code agent-runner}; сессии клиент не заводит — сервер её не выдаёт
 * (design.md, Р2).
 */
final class McpClients {

    private McpClients() {
    }

    static McpSyncClient withToken(int port, TurnToken token, JsonMapper jsonMapper) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint(Addresses.MCP)
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token.value()))
                .build();
        // Сборка PDF Задачи из кусков рисует страницы крупно; на медленной машине это дольше десяти секунд.
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }
}
