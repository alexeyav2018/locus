package ru.locus.assistant;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Addresses;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.user.Role;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Замок сервера MCP — токен Реплики (ADR-0057): без живого токена
 * ни один вызов не проходит, с живым клиент того же SDK видит
 * инструменты реестра, а страницы по-прежнему требуют входа формой.
 * Спека {@code assistant-tools}: «Сервер MCP живёт внутри приложения»
 * и «Инструмент зовётся только с живым токеном Реплики».
 */
class TurnTokenGuardsTheServerTest extends IntegrationTest {

    /** Первый запрос любого клиента MCP; без токена до сервера он дойти не должен. */
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private TurnTokens tokens;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private TestClock clock;
    @Autowired
    private ToolRegistry registry;
    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Сценарий «Вызов без токена». */
    @Test
    void withoutATokenTheServerRefusesBeforeAnyTool() {
        HttpResponse<String> response = post(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.body()).as("до сервера MCP запрос не дошёл").doesNotContain("\"result\"");
    }

    /** Сценарий «Отозванный токен». */
    @Test
    void revokedTokenIsRefusedLikeAnUnknownOne() {
        TurnToken token = tokens.issue(accounts.settled(Role.TEACHER).id(), Duration.ofMinutes(5));
        tokens.revoke(token);

        HttpResponse<String> revoked = post(token.value());
        HttpResponse<String> unknown = post("no-such-token");

        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(revoked.body()).isEqualTo(unknown.body());
    }

    /** Сценарий «Истёкший токен». */
    @Test
    void expiredTokenIsRefused() {
        TurnToken token = tokens.issue(accounts.settled(Role.TEACHER).id(), Duration.ofMinutes(1));
        clock.shift(Duration.ofMinutes(2));

        assertThat(post(token.value()).statusCode()).isEqualTo(401);
    }

    /** Сценарии «Живой токен» и «Клиент MCP подключается к работающему приложению». */
    @Test
    void liveTokenOfATeacherOpensTheToolsOfTheRegistry() {
        TurnToken token = tokens.issue(accounts.settled(Role.TEACHER).id(), Duration.ofMinutes(5));
        try (McpSyncClient client = client(token)) {
            McpSchema.InitializeResult initialized = client.initialize();

            assertThat(initialized.serverInfo().name()).isEqualTo("locus");
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyElementsOf(registry.names());
        } finally {
            tokens.revoke(token);
        }
    }

    /** Сценарий «Остальная система не зависит от сервера MCP»: вход формой как прежде. */
    @Test
    void pagesStillAskForTheLoginForm() {
        Browser browser = new Browser(port);

        assertThat(browser.get("/").redirectsTo("/login")).isTrue();
        assertThat(browser.get(Addresses.MCP).status())
                .as("а сервер MCP на форму входа не отправляет")
                .isEqualTo(401);
    }

    private HttpResponse<String> post(String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + Addresses.MCP))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private McpSyncClient client(TurnToken token) {
        return McpClients.withToken(port, token, jsonMapper);
    }
}
