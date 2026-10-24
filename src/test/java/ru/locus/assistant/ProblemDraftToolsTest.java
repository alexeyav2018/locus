package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.problem.AssemblyDraft;
import ru.locus.problem.AssemblyDraftService;
import ru.locus.problem.ExamPart;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemDraft;
import ru.locus.problem.ProblemDraftChange;
import ru.locus.problem.ProblemDraftContent;
import ru.locus.problem.ProblemDraftRepository;
import ru.locus.problem.ProblemDraftService;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Инструменты Черновика Задачи и заведения Метода — спека
 * {@code assistant-tools}: «Помощник ведёт Черновики Задачи своего учителя»
 * и «Администратор заводит Метод инструментом».
 *
 * <p>Инструмент зовёт тот же {@code ProblemDraftService}, что страница
 * вычитки, поэтому правка учителя на странице здесь — та же запись сервиса
 * с отметкой {@code OWNER}, какой её делает контроллер; сама страница
 * проверяется там, где видна отметка правки инструментом. Вызов — как его
 * видит модель, через {@link ToolCalls}; сквозной путь по HTTP —
 * {@code EveryToolAnswersTheClientTest}.
 */
class ProblemDraftToolsTest extends IntegrationTest {

    private static final String CONDITION = "Найдите $x$, если $\\log_2 ⟦8⟧ = x$.";
    private static final String SOLUTION = "По определению логарифма $x = 3$.";

    @LocalServerPort
    private int port;

    @Autowired
    private ToolRegistry registry;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private TestLibrary library;
    @Autowired
    private AssemblyDraftService assemblyDrafts;
    @Autowired
    private ProblemDraftService intake;
    @Autowired
    private ProblemDraftRepository repository;
    @Autowired
    private ProblemService problems;
    @Autowired
    private SolutionMethodService methods;

    private ToolCalls tools;
    private TestAccounts.Account administrator;
    private TaxonomyNodeId topic;
    private SolutionMethodId method;

    @BeforeEach
    void administratorWithTools() {
        tools = new ToolCalls(registry, jsonMapper);
        administrator = accounts.settled(Role.ADMINISTRATOR);
        topic = library.topic();
        method = library.method();
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        LoggedIn.nobody();
    }

    /** Сценарий «Заведение и чтение». */
    @Test
    void createdDraftReadsBackWithTheSameTextsLinesAndVersion() {
        AssemblyDraft sheet = upload(administrator);

        JsonNode created = tools.json(tools.call(administrator, "create_problem_draft", Map.of(
                "condition_text", CONDITION,
                "solution_text", SOLUTION,
                "condition", List.of(Map.of("draft", sheet.id().value(), "from", 1, "to", 1,
                        "frame", Map.of("left", 0.1, "top", 0.1, "width", 0.5, "height", 0.5))),
                "solution", List.of(Map.of("draft", sheet.id().value(), "from", 1, "to", 1)),
                "doubts", List.of("8 похоже на 9"),
                "part", "SECOND",
                "topics", List.of(topic.value()),
                "methods", List.of(method.value()),
                "new_methods", List.of("Метод интервалов"))));
        long id = created.get("id").asLong();
        JsonNode read = tools.json(tools.call(administrator, "get_problem_draft", Map.of("id", id)));

        assertThat(created.get("link").asString()).isEqualTo("/problems/intake/" + id);
        assertThat(read.get("version").asLong()).isEqualTo(created.get("version").asLong());
        assertThat(read.get("changed_by").asString()).isEqualTo("assistant");
        assertThat(read.get("condition_text").asString()).isEqualTo(CONDITION);
        assertThat(read.get("solution_text").asString()).isEqualTo(SOLUTION);
        JsonNode line = read.get("condition").get(0);
        assertThat(line.get("draft").asLong()).isEqualTo(sheet.id().value());
        assertThat(line.get("frame").get("width").asDouble()).isEqualTo(0.5);
        assertThat(read.get("solution").get(0).get("frame").isNull()).as("без Рамки — целая страница").isTrue();
        assertThat(read.get("doubts").get(0).asString()).isEqualTo("8 похоже на 9");
        assertThat(read.get("part").asString()).isEqualTo("SECOND");
        assertThat(read.get("topics").get(0).get("id").asLong()).isEqualTo(topic.value());
        assertThat(read.get("topics").get(0).get("path").asString()).isNotBlank();
        assertThat(read.get("methods").get(0).get("name").asString())
                .isEqualTo(methods.method(method).name());
        assertThat(read.get("new_methods").get(0).asString()).isEqualTo("Метод интервалов");

        JsonNode listed = tools.json(tools.call(administrator, "list_problem_drafts", Map.of()));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asLong()).isEqualTo(id);
    }

    /** Сценарий «Учитель поправил, Помощник пишет по старому». */
    @Test
    void writeWithTheVersionReadBeforeTheTeachersEditIsRefusedAndKeepsTheTeachersText() {
        ProblemDraft read = draft(administrator);
        LoggedIn.as(administrator);
        intake.write(read.id(), read.version(),
                ProblemDraftChange.of(withCondition(read.content(), "Условие учителя")), ProblemDraft.Editor.OWNER);
        LoggedIn.nobody();

        String refusal = ToolCalls.refusal(tools.call(administrator, "update_problem_draft", Map.of(
                "id", read.id().value(), "version", read.version(), "condition_text", "Условие Помощника")));

        assertThat(refusal).contains("перечитайте");
        LoggedIn.as(administrator);
        assertThat(intake.draft(read.id()).content().conditionText()).isEqualTo("Условие учителя");
    }

    /** Сценарий «Правка одной части». */
    @Test
    void writingOnlyTheSolutionLeavesEverythingElse() {
        ProblemDraft read = draft(administrator);

        JsonNode written = tools.json(tools.call(administrator, "update_problem_draft", Map.of(
                "id", read.id().value(), "version", read.version(), "solution_text", "Новое решение")));

        assertThat(written.get("version").asLong()).isEqualTo(read.version() + 1);
        LoggedIn.as(administrator);
        ProblemDraftContent content = intake.draft(read.id()).content();
        assertThat(content.solutionText()).isEqualTo("Новое решение");
        assertThat(content.conditionText()).isEqualTo(read.content().conditionText());
        assertThat(content.condition()).isEqualTo(read.content().condition());
        assertThat(content.doubts()).isEqualTo(read.content().doubts());
        assertThat(content.part()).isEqualTo(read.content().part());
        assertThat(content.topics()).isEqualTo(read.content().topics());
        assertThat(content.methods()).isEqualTo(read.content().methods());
    }

    /** Пустой список очищает часть, а отсутствующий аргумент её не трогает (design.md, Р7). */
    @Test
    void emptyListClearsAPartAndEmptyPartUnsetsIt() {
        ProblemDraft read = draft(administrator);

        tools.json(tools.call(administrator, "update_problem_draft", Map.of(
                "id", read.id().value(), "version", read.version(), "doubts", List.of(), "part", "")));

        LoggedIn.as(administrator);
        ProblemDraftContent content = intake.draft(read.id()).content();
        assertThat(content.doubts()).isEmpty();
        assertThat(content.part()).isNull();
        assertThat(content.methods()).containsExactly(method);
    }

    /** Сценарий «Чужой Черновик Задачи»: чужой и несуществующий — один отказ. */
    @Test
    void foreignAndMissingDraftsGiveTheSameRefusal() {
        ProblemDraft foreign = draft(administrator);
        TestAccounts.Account other = accounts.settled(Role.ADMINISTRATOR);
        long missing = foreign.id().value() + 1_000_000;

        String ofForeign = ToolCalls.refusal(tools.call(other, "get_problem_draft", Map.of("id", foreign.id().value())));
        String ofMissing = ToolCalls.refusal(tools.call(other, "get_problem_draft", Map.of("id", missing)));

        assertThat(ofForeign.replace(String.valueOf(foreign.id().value()), "N"))
                .isEqualTo(ofMissing.replace(String.valueOf(missing), "N"));
        assertThat(ToolCalls.refusal(tools.call(other, "delete_problem_draft",
                Map.of("id", foreign.id().value(), "version", foreign.version())))).isEqualTo(ofForeign);
        assertThat(ToolCalls.refusal(tools.call(other, "save_problem_draft",
                Map.of("id", foreign.id().value(), "version", foreign.version())))).isEqualTo(ofForeign);
        LoggedIn.as(administrator);
        assertThat(intake.draft(foreign.id()).version()).as("черновик А на месте").isEqualTo(foreign.version());
    }

    /** Сценарий «Сохранение инструментом». */
    @Test
    void savedDraftBecomesAProblemWithTextAndDisappears() {
        ProblemDraft read = draft(administrator);

        JsonNode saved = tools.json(tools.call(administrator, "save_problem_draft",
                Map.of("id", read.id().value(), "version", read.version())));

        Problem problem = problems.problem(new ProblemId(saved.get("number").asLong()));
        assertThat(problem.text()).isNotNull();
        assertThat(problem.text().condition()).as("пометка снята").isEqualTo("Найдите $x$, если $\\log_2 8 = x$.");
        assertThat(problem.part()).isEqualTo(ExamPart.SECOND);
        assertThat(repository.find(administrator.id(), read.id())).isEmpty();
    }

    /** Удаление с версией прочитанного; Задачи не заводит. */
    @Test
    void deletedDraftIsGone() {
        ProblemDraft read = draft(administrator);

        JsonNode deleted = tools.json(tools.call(administrator, "delete_problem_draft",
                Map.of("id", read.id().value(), "version", read.version())));

        assertThat(deleted.get("id").asLong()).isEqualTo(read.id().value());
        assertThat(repository.find(administrator.id(), read.id())).isEmpty();
    }

    /** Сценарий «Строка по чужому черновику сборки». */
    @Test
    void lineOnAForeignAssemblyDraftIsRefusedAsAMissingOneAndNoDraftIsCreated() {
        AssemblyDraft foreign = upload(administrator);
        TestAccounts.Account other = accounts.settled(Role.ADMINISTRATOR);
        long missing = foreign.id().value() + 1_000_000;

        String ofForeign = ToolCalls.refusal(tools.call(other, "create_problem_draft",
                Map.of("condition", List.of(Map.of("draft", foreign.id().value(), "from", 1, "to", 1)))));
        String ofMissing = ToolCalls.refusal(tools.call(other, "create_problem_draft",
                Map.of("condition", List.of(Map.of("draft", missing, "from", 1, "to", 1)))));

        assertThat(ofForeign.replace(String.valueOf(foreign.id().value()), "N"))
                .isEqualTo(ofMissing.replace(String.valueOf(missing), "N"));
        assertThat(repository.findByOwner(other.id())).isEmpty();
    }

    /** Учитель без роли Администратора черновиков не ведёт (design.md, Р2). */
    @Test
    void teacherWithoutAdministratorRoleIsRefused() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);

        assertThat(ToolCalls.refusal(tools.call(teacher, "list_problem_drafts", Map.of())))
                .isEqualTo(ToolAdapter.NO_RIGHTS);
        assertThat(ToolCalls.refusal(tools.call(teacher, "create_method", Map.of("name", "Метод учителя"))))
                .isEqualTo(ToolAdapter.NO_RIGHTS);
    }

    /** Сценарии «Новый Метод» и «Повтор имени». */
    @Test
    void createMethodAddsToTheDictionaryAndRefusesARepeatInAnotherCase() {
        String name = TestLibrary.unique("Метод Помощника");

        JsonNode created = tools.json(tools.call(administrator, "create_method", Map.of("name", name)));
        JsonNode listed = tools.json(tools.call(administrator, "list_methods", Map.of()));
        String repeat = ToolCalls.refusal(tools.call(administrator, "create_method",
                Map.of("name", name.toUpperCase())));

        long id = created.get("id").asLong();
        assertThat(created.get("name").asString()).isEqualTo(name);
        assertThat(listed.valueStream().map(entry -> entry.get("id").asLong())).contains(id);
        assertThat(repeat).isNotBlank();
        assertThat(methods.all().stream().filter(entry -> entry.name().equalsIgnoreCase(name))).hasSize(1);
    }

    /** Правка инструментом — «изменён Помощником» на странице вычитки. */
    @Test
    void pageMarksTheToolsEditAsTheAssistants() {
        ProblemDraft read = draft(administrator);
        tools.json(tools.call(administrator, "update_problem_draft", Map.of(
                "id", read.id().value(), "version", read.version(), "solution_text", "Решение Помощника")));

        Browser browser = new Browser(port);
        browser.logIn(administrator.login(), administrator.password());
        String body = browser.get("/problems/intake/" + read.id().value()).body();

        assertThat(body).contains("изменён Помощником").contains("Решение Помощника");
    }

    /** Черновик учителя с текстами и разметкой — заведён страницей, без строк сборки. */
    private ProblemDraft draft(TestAccounts.Account owner) {
        LoggedIn.as(owner);
        try {
            return intake.create(new ProblemDraftContent(ExamPart.SECOND, CONDITION, SOLUTION, null, null,
                    List.of("8 похоже на 9"), List.of(topic), List.of(method), null, null),
                    ProblemDraft.Editor.OWNER);
        } finally {
            LoggedIn.nobody();
        }
    }

    private static ProblemDraftContent withCondition(ProblemDraftContent content, String condition) {
        return new ProblemDraftContent(content.part(), condition, content.solutionText(), content.condition(),
                content.solution(), content.doubts(), content.topics(), content.methods(),
                content.characteristics(), content.newMethods());
    }

    private AssemblyDraft upload(TestAccounts.Account owner) {
        LoggedIn.as(owner);
        try {
            return assemblyDrafts.upload("лист.pdf", new ByteArrayInputStream(TestLibrary.renderablePdf(1)));
        } finally {
            LoggedIn.nobody();
        }
    }
}
