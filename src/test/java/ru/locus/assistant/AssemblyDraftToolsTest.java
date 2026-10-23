package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.AssemblyDraft;
import ru.locus.problem.AssemblyDraftService;
import ru.locus.user.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Инструменты Черновиков сборки глазами Помощника — спека
 * {@code assistant-tools}: «Черновики сборки отдаются только их владельцу»,
 * «Отказ сервиса возвращается Помощнику текстом» (неверные аргументы)
 * и отказ Учителю без роли Администратора.
 *
 * <p>Два Администратора с черновиком у каждого: изоляцию держит сервис,
 * а инструмент должен её не испортить — не отличать чужой черновик
 * от несуществующего ни текстом отказа, ни чем-либо ещё.
 */
class AssemblyDraftToolsTest extends IntegrationTest {

    /** Страница A4 в точках PDF — такой её делает {@link TestLibrary#renderablePdf}. */
    private static final double A4_WIDTH = 595.28;
    private static final double A4_HEIGHT = 841.89;

    private static final Map<String, Object> UPPER_LEFT_QUARTER =
            Map.of("left", 0.0, "top", 0.0, "width", 0.5, "height", 0.5);

    @Autowired
    private ToolRegistry registry;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private AssemblyDraftService drafts;

    private ToolCalls tools;
    private TestAccounts.Account alice;
    private TestAccounts.Account bob;
    private AssemblyDraft alicesBook;
    private AssemblyDraft bobsSheet;

    @BeforeEach
    void twoAdministratorsWithADraftEach() {
        tools = new ToolCalls(registry, jsonMapper);
        alice = accounts.settled(Role.ADMINISTRATOR);
        bob = accounts.settled(Role.ADMINISTRATOR);
        alicesBook = upload(alice, "сборник.pdf", TestLibrary.renderablePdf(3));
        bobsSheet = upload(bob, "лист.pdf", TestLibrary.renderablePdf(1));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Список своих черновиков». */
    @Test
    void listShowsOwnDraftsAndNotAnotherAdministrators() {
        JsonNode list = tools.json(tools.call(alice, "list_assembly_drafts", Map.of()));

        assertThat(ids(list)).contains(alicesBook.id().value()).doesNotContain(bobsSheet.id().value());
        JsonNode book = stream(list)
                .filter(entry -> entry.get("id").asLong() == alicesBook.id().value())
                .findFirst()
                .orElseThrow();
        assertThat(book.get("name").asString()).isEqualTo("сборник.pdf");
        assertThat(book.get("kind").asString()).isEqualTo("PDF");
        assertThat(book.get("pages").asInt()).isEqualTo(3);
    }

    /** Страница своего черновика — JPEG обычного размера, как на странице сборки. */
    @Test
    void pageOfOwnDraftIsAJpegOfTheNormalSize() throws IOException {
        byte[] jpeg = ToolCalls.jpeg(tools.call(alice, "get_assembly_draft_page", page(alicesBook, 2)));

        BufferedImage shown = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(shown.getHeight()).isEqualTo(1600);
        assertThat((double) shown.getWidth() / shown.getHeight()).isCloseTo(A4_WIDTH / A4_HEIGHT, within(0.01));
    }

    /**
     * Сценарий «Чужой черновик неотличим от несуществующего» — и страница
     * вне черновика, и кусок по рамке отвечают тем же текстом, без идентификатора.
     */
    @Test
    void anotherAdministratorsDraftAndAMissingOneGiveTheSameRefusal() {
        Map<String, Object> missing = page(alicesBook.id().value() + 1_000_000, 1);

        String foreignPage = ToolCalls.refusal(tools.call(bob, "get_assembly_draft_page", page(alicesBook, 1)));
        String missingPage = ToolCalls.refusal(tools.call(bob, "get_assembly_draft_page", missing));
        String beyondPage = ToolCalls.refusal(tools.call(alice, "get_assembly_draft_page", page(alicesBook, 4)));
        String foreignPiece = ToolCalls.refusal(
                tools.call(bob, "crop_assembly_draft_page", framed(page(alicesBook, 1))));
        String missingPiece = ToolCalls.refusal(tools.call(bob, "crop_assembly_draft_page", framed(missing)));

        assertThat(List.of(missingPage, beyondPage, foreignPiece, missingPiece)).containsOnly(foreignPage);
        assertThat(foreignPage).doesNotContain(String.valueOf(alicesBook.id().value()));
    }

    /** Сценарий «Кусок страницы по Рамке». */
    @Test
    void pieceByTheUpperLeftQuarterHasTheFrameProportions() throws IOException {
        byte[] jpeg = ToolCalls.jpeg(tools.call(alice, "crop_assembly_draft_page", framed(page(alicesBook, 1))));

        BufferedImage piece = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat((double) piece.getWidth() / piece.getHeight())
                .as("четверть A4 — те же пропорции, что у страницы")
                .isCloseTo(A4_WIDTH / A4_HEIGHT, within(0.01));
        assertThat(piece.getHeight()).as("четверть приходит в разрешении целой страницы").isEqualTo(1600);
    }

    /** Сценарий «Учитель без роли Администратора». */
    @Test
    void teacherWithoutAdministratorRoleIsRefusedForLackOfRights() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);

        assertThat(ToolCalls.refusal(tools.call(teacher, "list_assembly_drafts", Map.of())))
                .isEqualTo(ToolAdapter.NO_RIGHTS);
        assertThat(ToolCalls.refusal(tools.call(teacher, "get_assembly_draft_page", page(alicesBook, 1))))
                .isEqualTo(ToolAdapter.NO_RIGHTS);
        assertThat(ToolCalls.refusal(tools.call(teacher, "crop_assembly_draft_page", framed(page(alicesBook, 1)))))
                .isEqualTo(ToolAdapter.NO_RIGHTS);
    }

    /** Сценарий «Неверные аргументы»: без номера страницы — отказ, называющий аргумент. */
    @Test
    void missingPageArgumentIsRefusedNamingIt() {
        String refusal = ToolCalls.refusal(tools.call(alice, "get_assembly_draft_page",
                Map.of("draft", alicesBook.id().value())));

        assertThat(refusal).contains("page");
    }

    private AssemblyDraft upload(TestAccounts.Account owner, String name, byte[] content) {
        LoggedIn.as(owner);
        try {
            return drafts.upload(name, new ByteArrayInputStream(content));
        } finally {
            LoggedIn.nobody();
        }
    }

    private static Map<String, Object> page(AssemblyDraft draft, int page) {
        return page(draft.id().value(), page);
    }

    private static Map<String, Object> page(long draft, int page) {
        return Map.of("draft", draft, "page", page);
    }

    private static Map<String, Object> framed(Map<String, Object> page) {
        Map<String, Object> arguments = new HashMap<>(page);
        arguments.put("frame", UPPER_LEFT_QUARTER);
        return arguments;
    }

    private static List<Long> ids(JsonNode array) {
        return stream(array).map(node -> node.get("id").asLong()).toList();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        assertThat(array.isArray()).as("ответ — список").isTrue();
        return IntStream.range(0, array.size()).mapToObj(array::get);
    }
}
