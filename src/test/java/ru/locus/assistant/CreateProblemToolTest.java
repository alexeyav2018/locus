package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.file.FilePages;
import ru.locus.file.PdfOutline;
import ru.locus.problem.AssemblyDraft;
import ru.locus.problem.AssemblyDraftRepository;
import ru.locus.problem.AssemblyDraftService;
import ru.locus.problem.ExamPart;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.user.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Заведение Задачи инструментом — спека {@code assistant-tools}:
 * «Администратор заводит Задачу инструментом из Черновиков сборки»
 * и «Инструмент действует под личностью учителя теми же сервисами»
 * в части отказа Учителю без роли Администратора.
 *
 * <p>Проверки — те же, что у формы, потому что зовётся тот же
 * {@code ProblemService.create}: инструмент только переводит аргументы
 * в порядок сборки. Отказ в правах приходит от {@code @PreAuthorize}
 * сервиса и доходит до модели текстом, а не разрывом соединения.
 */
class CreateProblemToolTest extends IntegrationTest {

    /** Страница A4 в точках PDF — такой её делает {@link TestLibrary#renderablePdf}. */
    private static final double A4_WIDTH = 595.28;
    private static final double A4_HEIGHT = 841.89;

    @Autowired
    private ToolRegistry registry;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private TestLibrary library;
    @Autowired
    private AssemblyDraftService drafts;
    @Autowired
    private AssemblyDraftRepository draftRepository;
    @Autowired
    private ProblemService problems;
    @Autowired
    private FilePages filePages;

    private ToolCalls tools;
    private TestAccounts.Account administrator;

    @BeforeEach
    void administratorWithTools() {
        tools = new ToolCalls(registry, jsonMapper);
        administrator = accounts.settled(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Задача из куска страницы». */
    @Test
    void administratorCreatesAProblemFromAFramedPieceOfOwnDraft() {
        AssemblyDraft book = upload("сборник.pdf", TestLibrary.renderablePdf(2));
        Map<String, Object> arguments = Map.of(
                "part", "FIRST",
                "topics", List.of(library.topic().value()),
                "methods", List.of(library.method().value()),
                "condition", List.of(Map.of("draft", book.id().value(), "from", 1, "to", 1,
                        "frame", Map.of("left", 0.0, "top", 0.0, "width", 0.5, "height", 0.25))),
                "solution", List.of(Map.of("draft", book.id().value(), "from", 2, "to", 2)));

        JsonNode created = tools.json(tools.call(administrator, "create_problem", arguments));

        ProblemId id = new ProblemId(created.get("number").asLong());
        Problem problem = problems.problem(id);
        assertThat(problem.part()).isEqualTo(ExamPart.FIRST);
        PdfOutline condition = filePages.outline(problem.conditionFile()).orElseThrow();
        assertThat(condition.pages()).as("кусок — одна страница условия").isEqualTo(1);
        PdfOutline.PageSize piece = condition.shown().getFirst();
        assertThat((double) piece.width() / piece.height())
                .as("пропорции куска — пропорции Рамки на странице A4")
                .isCloseTo(0.5 * A4_WIDTH / (0.25 * A4_HEIGHT), within(0.03));
        assertThat(filePages.outline(problem.solutionFile()).orElseThrow().pages())
                .as("решение — одна целая страница").isEqualTo(1);
        assertThat(draftRepository.findByIds(administrator.id(), List.of(book.id())))
                .as("черновик удалён, как после сохранения формы").isEmpty();
    }

    /** Сценарий «Разметка нарушена»: без Метода и с Разделом вместо Темы — отказ сервиса. */
    @Test
    void brokenMarkupIsRefusedWithTheServiceTextAndKeepsTheDraft() {
        AssemblyDraft sheet = upload("лист.pdf", TestLibrary.renderablePdf(1));
        Map<String, Object> withoutMethod = Map.of(
                "part", "SECOND",
                "topics", List.of(library.topic().value()),
                "condition", List.of(wholePage(sheet)),
                "solution", List.of(wholePage(sheet)));
        Map<String, Object> onASection = Map.of(
                "part", "SECOND",
                "topics", List.of(library.section().value()),
                "methods", List.of(library.method().value()),
                "condition", List.of(wholePage(sheet)),
                "solution", List.of(wholePage(sheet)));

        assertThat(ToolCalls.refusal(tools.call(administrator, "create_problem", withoutMethod)))
                .contains("Метод");
        assertThat(ToolCalls.refusal(tools.call(administrator, "create_problem", onASection)))
                .contains("Раздел");
        assertThat(draftRepository.findByIds(administrator.id(), List.of(sheet.id())))
                .as("отказ оставляет черновик, как у формы").hasSize(1);
    }

    /** Сценарий «Заведение Задачи Учителем без роли Администратора». */
    @Test
    void teacherWithoutAdministratorRoleIsRefusedForLackOfRights() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        Map<String, Object> arguments = Map.of(
                "part", "SECOND",
                "topics", List.of(library.topic().value()),
                "methods", List.of(library.method().value()),
                "condition", List.of(Map.of("draft", 1, "from", 1, "to", 1)),
                "solution", List.of(Map.of("draft", 1, "from", 1, "to", 1)));

        assertThat(ToolCalls.refusal(tools.call(teacher, "create_problem", arguments)))
                .isEqualTo(ToolAdapter.NO_RIGHTS);
    }

    private AssemblyDraft upload(String name, byte[] content) {
        LoggedIn.as(administrator);
        try {
            return drafts.upload(name, new ByteArrayInputStream(content));
        } finally {
            LoggedIn.nobody();
        }
    }

    private static Map<String, Object> wholePage(AssemblyDraft draft) {
        return Map.of("draft", draft.id().value(), "from", 1, "to", 1);
    }
}
