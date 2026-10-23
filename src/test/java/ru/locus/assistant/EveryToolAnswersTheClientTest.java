package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.problem.AssemblyDraft;
import ru.locus.problem.AssemblyDraftService;
import ru.locus.problem.ProblemId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сквозной путь Помощника по HTTP: клиент того же SDK с живым токеном
 * Администратора перечисляет инструменты и зовёт каждый из реестра
 * с годными аргументами — и ни один не отвечает отказом. Спека
 * {@code assistant-tools}: «Список сервера равен реестру» и «Страница PDF
 * Задачи отдаётся картинкой»; design.md, Р3 — перенос личности в поток
 * обработчика: SDK выполняет инструмент не на потоке сервлета, и забытый
 * перенос оставил бы его без вошедшего — здесь это упало бы на первом же
 * инструменте, читающем {@code CurrentUser}, а не в работе у учителя.
 *
 * <p>Инструмент реестра, которого этот тест не позвал, роняет его: новый
 * инструмент дописывается в {@link #calls} вместе с годными аргументами,
 * иначе его сквозной путь никто не проверит. Поведение каждого инструмента
 * в подробностях — {@code LibraryToolsTest}, {@code CreateProblemToolTest}
 * и {@code AssemblyDraftToolsTest}; здесь только то, что он отвечает модели.
 */
class EveryToolAnswersTheClientTest extends IntegrationTest {

    /** Инструменты, отвечающие картинкой; остальные отвечают JSON. */
    private static final Set<String> IMAGES = Set.of(
            "get_problem_page", "get_assembly_draft_page", "crop_assembly_draft_page");

    @LocalServerPort
    private int port;

    @Autowired
    private TurnTokens tokens;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private TestLibrary library;
    @Autowired
    private AssemblyDraftService drafts;
    @Autowired
    private ToolRegistry registry;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void everyToolOfTheRegistryAnswersTheClientWithoutAnError() {
        TestAccounts.Account administrator = accounts.settled(Role.ADMINISTRATOR);
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        SolutionMethodId method = library.method();
        ProblemId problem = library.renderableProblem(topic);
        AssemblyDraft book = upload(administrator, "сборник.pdf", TestLibrary.renderablePdf(2));
        Map<String, Map<String, Object>> calls = calls(section, topic, method, problem, book);

        TurnToken token = tokens.issue(administrator.id(), Duration.ofMinutes(5));
        try (McpSyncClient client = McpClients.withToken(port, token, jsonMapper)) {
            client.initialize();
            List<String> offered = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            assertThat(offered).as("сервер предлагает ровно реестр").containsExactlyElementsOf(registry.names());
            assertThat(calls.keySet())
                    .as("каждый инструмент реестра позван этим тестом; новый — дописать в calls с годными аргументами")
                    .containsExactlyInAnyOrderElementsOf(offered);

            calls.forEach((tool, arguments) -> {
                McpSchema.CallToolResult result = client.callTool(
                        McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());

                assertThat(result.isError()).as("%s ответил отказом: %s", tool, ToolCalls.text(result)).isFalse();
                if (IMAGES.contains(tool)) {
                    assertThat(ToolCalls.jpeg(result)).as("%s — картинка JPEG", tool).isNotEmpty();
                } else {
                    JsonNode data = jsonMapper.readTree(ToolCalls.text(result));
                    assertThat(data.isArray() || data.isObject()).as("%s — данные JSON: %s", tool, data).isTrue();
                }
            });
        } finally {
            tokens.revoke(token);
        }
    }

    /**
     * Годные аргументы каждого инструмента первой очереди. {@code create_problem} —
     * последним: заведя Задачу, он удаляет черновик, из которого она собрана,
     * а черновик нужен инструментам Черновиков сборки до него.
     */
    private static Map<String, Map<String, Object>> calls(TaxonomyNodeId section, TaxonomyNodeId topic,
                                                          SolutionMethodId method, ProblemId problem,
                                                          AssemblyDraft book) {
        long draft = book.id().value();
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put("list_topics", Map.of());
        calls.put("list_methods", Map.of());
        calls.put("list_characteristics", Map.of());
        calls.put("find_problems", Map.of("node", section.value(), "methods", List.of(method.value())));
        calls.put("get_problem", Map.of("number", problem.value()));
        calls.put("get_problem_page", Map.of("number", problem.value(), "slot", "condition", "page", 1));
        calls.put("list_assembly_drafts", Map.of());
        calls.put("get_assembly_draft_page", Map.of("draft", draft, "page", 1));
        calls.put("crop_assembly_draft_page", Map.of("draft", draft, "page", 1,
                "frame", Map.of("left", 0.0, "top", 0.0, "width", 0.5, "height", 0.5)));
        calls.put("create_problem", Map.of(
                "part", "FIRST",
                "topics", List.of(topic.value()),
                "methods", List.of(method.value()),
                "condition", List.of(Map.of("draft", draft, "from", 1, "to", 1,
                        "frame", Map.of("left", 0.0, "top", 0.0, "width", 0.5, "height", 0.25))),
                "solution", List.of(Map.of("draft", draft, "from", 2, "to", 2))));
        return calls;
    }

    private AssemblyDraft upload(TestAccounts.Account owner, String name, byte[] content) {
        LoggedIn.as(owner);
        try {
            return drafts.upload(name, new ByteArrayInputStream(content));
        } finally {
            LoggedIn.nobody();
        }
    }
}
