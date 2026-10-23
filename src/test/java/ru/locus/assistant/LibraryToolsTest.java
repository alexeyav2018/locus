package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Инструменты библиотеки — дерево, словари, Задачи — глазами Помощника.
 * Спека {@code assistant-tools}: «Дерево и словари отдаются списками»,
 * «Задачи находятся и читаются теми же условиями, что на странице поиска»,
 * «Страница PDF Задачи отдаётся картинкой», «Отказ сервиса возвращается
 * Помощнику текстом» и «Библиотека одинакова для разных учителей».
 *
 * <p>Вызовы идут через реестр и обёртку инструмента от имени вошедшего,
 * без HTTP: здесь проверяется, что отдаёт инструмент, а не как ответ
 * доходит до клиента. Библиотека общая, и отвечает она Учителю — тому,
 * ради кого Помощник заведён.
 */
class LibraryToolsTest extends IntegrationTest {

    @Autowired
    private ToolRegistry registry;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private TestLibrary library;
    @Autowired
    private TaxonomyService taxonomy;
    @Autowired
    private SolutionMethodService methods;
    @Autowired
    private CharacteristicService characteristics;

    private ToolCalls tools;
    private TestAccounts.Account teacher;

    @BeforeEach
    void teacherWithTools() {
        tools = new ToolCalls(registry, jsonMapper);
        teacher = accounts.settled(Role.TEACHER);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Темы с путями». */
    @Test
    void listTopicsGivesLeavesWithFullPathsAndNoSections() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);

        JsonNode topics = tools.json(tools.call(teacher, "list_topics", Map.of()));

        assertThat(byId(topics, topic.value())).isPresent();
        assertThat(byId(topics, topic.value()).orElseThrow().get("path").asString())
                .isEqualTo(taxonomy.node(section).name() + " / " + taxonomy.node(topic).name());
        assertThat(byId(topics, section.value())).as("Раздел в списке Тем не предлагается").isEmpty();
    }

    /** Сценарий «Словари». */
    @Test
    void dictionariesAreListedWholeWithIdsAndNames() {
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();

        JsonNode listedMethods = tools.json(tools.call(teacher, "list_methods", Map.of()));
        JsonNode listedCharacteristics = tools.json(tools.call(teacher, "list_characteristics", Map.of()));

        assertThat(listedMethods.size()).isEqualTo(methods.all().size());
        assertThat(byId(listedMethods, method.value()).orElseThrow().get("name").asString())
                .isEqualTo(methods.method(method).name());
        assertThat(listedCharacteristics.size()).isEqualTo(characteristics.all().size());
        assertThat(byId(listedCharacteristics, characteristic.value()).orElseThrow().get("name").asString())
                .isEqualTo(characteristics.characteristic(characteristic).name());
    }

    /** Сценарий «Поиск по узлу и Методу». */
    @Test
    void findProblemsBySectionAndMethodGivesTheSubtreeMarkedWithIt() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        SolutionMethodId method = library.method();
        ProblemId wanted = library.problem(topic, method);
        library.problem(topic);                    // та же Тема, другой Метод
        library.problem(library.topic(), method);  // тот же Метод, другая ветка

        JsonNode found = tools.json(tools.call(teacher, "find_problems",
                Map.of("node", section.value(), "methods", List.of(method.value()))));

        assertThat(numbers(found)).containsExactly(wanted.value());
        JsonNode row = found.get(0);
        assertThat(row.get("part").asString()).isEqualTo("SECOND");
        assertThat(row.get("topics").get(0).asString())
                .isEqualTo(taxonomy.node(section).name() + " / " + taxonomy.node(topic).name());
        assertThat(row.get("methods").get(0).asString()).isEqualTo(methods.method(method).name());
        assertThat(row.get("characteristics").size()).isZero();
    }

    /** Способ соединения — как на странице поиска (ADR-0031): «все сразу» сужает, «любое из» расширяет. */
    @Test
    void matchModeNarrowsOrWidensAsOnTheSearchPage() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId first = library.method();
        SolutionMethodId second = library.method();
        ProblemId both = library.problem(List.of(topic), List.of(first, second), List.of(), ExamPart.FIRST);
        ProblemId onlyFirst = library.problem(topic, first);
        List<Long> chosen = List.of(first.value(), second.value());

        JsonNode all = tools.json(tools.call(teacher, "find_problems",
                Map.of("node", topic.value(), "methods", chosen, "methods_match", "all")));
        JsonNode any = tools.json(tools.call(teacher, "find_problems",
                Map.of("node", topic.value(), "methods", chosen, "methods_match", "any")));

        assertThat(numbers(all)).containsExactly(both.value());
        assertThat(numbers(any)).containsExactlyInAnyOrder(both.value(), onlyFirst.value());
    }

    /** Сценарий «Чтение Задачи». */
    @Test
    void getProblemGivesMarkupWithIdsAndPageCounts() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        ProblemId id = library.renderableProblem(topic);

        JsonNode problem = tools.json(tools.call(teacher, "get_problem", Map.of("number", id.value())));

        assertThat(problem.get("number").asLong()).isEqualTo(id.value());
        assertThat(problem.get("part").asString()).isEqualTo("SECOND");
        assertThat(problem.get("topics").get(0).get("id").asLong()).isEqualTo(topic.value());
        assertThat(problem.get("topics").get(0).get("path").asString())
                .isEqualTo(taxonomy.node(section).name() + " / " + taxonomy.node(topic).name());
        JsonNode method = problem.get("methods").get(0);
        assertThat(method.get("name").asString())
                .isEqualTo(methods.method(new SolutionMethodId(method.get("id").asLong())).name());
        assertThat(problem.get("characteristics").size()).isZero();
        assertThat(problem.get("condition_pages").asInt()).isEqualTo(1);
        assertThat(problem.get("solution_pages").asInt()).isEqualTo(1);
    }

    /** Сценарий «Несуществующая Задача». */
    @Test
    void missingProblemIsARefusalSayingItDoesNotExist() {
        String refusal = ToolCalls.refusal(tools.call(teacher, "get_problem", Map.of("number", 999_999_999L)));

        assertThat(refusal).contains("не существует");
    }

    /** Сценарии «Страница условия» и «Страница вне файла». */
    @Test
    void problemPageIsAJpegAndAPageBeyondTheFileIsARefusal() {
        ProblemId id = library.renderableProblem(library.topic());

        assertThat(ToolCalls.jpeg(tools.call(teacher, "get_problem_page",
                Map.of("number", id.value(), "slot", "condition", "page", 1)))).isNotEmpty();
        assertThat(ToolCalls.jpeg(tools.call(teacher, "get_problem_page",
                Map.of("number", id.value(), "slot", "solution", "page", 1)))).isNotEmpty();
        assertThat(ToolCalls.refusal(tools.call(teacher, "get_problem_page",
                Map.of("number", id.value(), "slot", "condition", "page", 2)))).contains("нет страницы 2");
    }

    /** Сценарий «Неверные аргументы»: отказ называет аргумент. */
    @Test
    void wrongArgumentIsARefusalNamingIt() {
        assertThat(ToolCalls.refusal(tools.call(teacher, "find_problems", Map.of("part", "THIRD"))))
                .contains("«part»");
        assertThat(ToolCalls.refusal(tools.call(teacher, "get_problem_page",
                Map.of("number", 1, "slot", "condition")))).contains("«page»");
    }

    /** Сценарий «Библиотека одинакова для разных учителей»: фильтра по владельцу нет. */
    @Test
    void twoTeachersGetTheSameSearchResult() {
        TaxonomyNodeId topic = library.topic();
        library.problem(topic);
        library.problem(topic);
        TestAccounts.Account another = accounts.settled(Role.TEACHER);
        Map<String, Object> arguments = Map.of("node", topic.value());

        McpSchema.CallToolResult mine = tools.call(teacher, "find_problems", arguments);
        McpSchema.CallToolResult theirs = tools.call(another, "find_problems", arguments);

        assertThat(numbers(tools.json(mine))).hasSize(2);
        assertThat(ToolCalls.text(theirs)).isEqualTo(ToolCalls.text(mine));
    }

    private static Optional<JsonNode> byId(JsonNode array, long id) {
        return stream(array).filter(node -> node.get("id").asLong() == id).findFirst();
    }

    private static List<Long> numbers(JsonNode array) {
        return stream(array).map(node -> node.get("number").asLong()).toList();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        assertThat(array.isArray()).as("ответ — список").isTrue();
        return IntStream.range(0, array.size()).mapToObj(i -> array.get(i));
    }
}
