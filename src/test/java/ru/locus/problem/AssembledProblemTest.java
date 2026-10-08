package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Задача 3.1: слот Задачи принимает порядок сборки наравне с готовым PDF;
 * черновики уходят только после того, как Задача сохранена (ADR-0044).
 *
 * Содержимое проверяется так же, как в {@link ProblemFilesTest}: файлом
 * по выданной ссылке, другого пути к нему нет.
 */
class AssembledProblemTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ProblemService problems;

    @Autowired
    private AssemblyDraftService service;

    @Autowired
    private AssemblyDraftRepository drafts;

    @Autowired
    private AssemblyDraftProperties properties;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private TestAccounts.Account administrator;

    @BeforeEach
    void administratorLoggedIn() {
        administrator = accounts.settled(Role.ADMINISTRATOR);
        LoggedIn.as(administrator);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Задача с собранным условием и готовым решением». */
    @Test
    void problemWithAnAssembledConditionAndAReadySolution() throws IOException {
        AssemblyDraft first = upload("1.png", AssemblyDraftServiceTest.png());
        AssemblyDraft second = upload("2.png", AssemblyDraftServiceTest.png());
        AssemblyDraft third = upload("3.png", AssemblyDraftServiceTest.png());
        byte[] solution = TestLibrary.pdf();

        ProblemId id = create(order(first, second, third), new UploadedFile(solution, FileType.PDF));

        Browser browser = new Browser(port);
        try (PDDocument condition = Loader.loadPDF(browser.getBytes(problems.conditionLink(id).toString()))) {
            assertThat(condition.getNumberOfPages()).as("три картинки — три страницы").isEqualTo(3);
        }
        assertThat(browser.getBytes(problems.solutionLink(id).toString()))
                .as("готовое решение — байт в байт")
                .isEqualTo(solution);
    }

    /** Сценарий «Один сборник на условие и решение» и «Черновики уходят после сборки». */
    @Test
    void draftsOfBothSlotsAreGoneOnceTheProblemIsSaved() throws IOException {
        AssemblyDraft book = upload("сборник.pdf", AssemblyDraftServiceTest.pdf(5));
        PdfAssemblyOrder condition = new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(book.id(), 1, 2)));
        PdfAssemblyOrder solution = new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(book.id(), 5, 5)));

        ProblemId id = create(condition, solution);

        assertThat(problems.problem(id).id()).isEqualTo(id);
        assertThat(drafts.findByIds(administrator.id(), List.of(book.id()))).as("строки нет").isEmpty();
        assertThat(properties.directory().resolve(book.fileName())).as("файла нет").doesNotExist();
    }

    /** Сценарий «Отказ формы не теряет черновики». */
    @Test
    void refusedProblemKeepsItsDrafts() throws IOException {
        AssemblyDraft photo = upload("снимок.png", AssemblyDraftServiceTest.png());
        TaxonomyNodeId topic = library.topic();

        assertThatThrownBy(() -> problems.create(ExamPart.FIRST, List.of(topic), List.of(), List.of(),
                order(photo), new UploadedFile(TestLibrary.pdf(), FileType.PDF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы один Метод");

        assertThat(drafts.findByIds(administrator.id(), List.of(photo.id()))).hasSize(1);
        assertThat(properties.directory().resolve(photo.fileName())).exists();
    }

    /** Сценарий «Замена файла сборкой»: прежний файл уходит, черновик тоже. */
    @Test
    void solutionIsReplacedByAnAssembledOne() throws IOException {
        ProblemId id = library.problem(library.topic());
        String oldLink = problems.solutionLink(id).toString();
        AssemblyDraft photo = upload("снимок.png", AssemblyDraftServiceTest.png());

        problems.replaceSolution(id, order(photo));

        Browser browser = new Browser(port);
        try (PDDocument solution = Loader.loadPDF(browser.getBytes(problems.solutionLink(id).toString()))) {
            assertThat(solution.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(browser.getBytes(oldLink)).as("прежнее решение удалено").isEmpty();
        assertThat(drafts.findByIds(administrator.id(), List.of(photo.id()))).isEmpty();
    }

    /**
     * Сценарий «Кусок векторной страницы» сквозь форму (ADR-0045): две строки
     * одного сборника со своими рамками, поля — как их шлёт скрипт после
     * «обвести» на диапазоне «с 2 по 3».
     */
    @Test
    void conditionIsAssembledFromPiecesOfTwoPagesSentByTheForm() throws IOException {
        AssemblyDraft book = upload("сборник.pdf", AssemblyDraftServiceTest.pdf(5));
        Browser browser = loggedInBrowser();
        List<Map.Entry<String, String>> fields = markup();
        fields.addAll(line(book, 2, 2, "0.0000;0.0000;0.5000;0.2500"));
        fields.addAll(line(book, 3, 3, "0.2500;0.5000;0.5000;0.5000"));

        Browser.Page created = browser.postMultipart("/problems", fields, List.of(solutionPart()));

        assertThat(created.status()).as("Задача заведена").isEqualTo(302);
        ProblemId id = new ProblemId(Long.parseLong(created.location().replaceAll(".*/", "")));
        try (PDDocument condition = Loader.loadPDF(browser.getBytes(problems.conditionLink(id).toString()))) {
            assertThat(condition.getNumberOfPages()).as("два куска — две страницы").isEqualTo(2);
            PDRectangle first = condition.getPage(0).getCropBox();
            assertThat(first.getWidth()).isCloseTo(306f, within(0.5f));
            assertThat(first.getHeight()).isCloseTo(198f, within(0.5f));
            PDRectangle second = condition.getPage(1).getCropBox();
            assertThat(second.getWidth()).isCloseTo(306f, within(0.5f));
            assertThat(second.getHeight()).isCloseTo(396f, within(0.5f));
        }
    }

    /** Рамка на диапазоне — отказ формы, строки нарисованы заново, черновик цел. */
    @Test
    void frameOnARangeIsRefusedWithoutLosingTheRows() throws IOException {
        AssemblyDraft book = upload("сборник.pdf", AssemblyDraftServiceTest.pdf(5));
        Browser browser = loggedInBrowser();
        List<Map.Entry<String, String>> fields = markup();
        fields.addAll(line(book, 1, 1, ""));
        fields.addAll(line(book, 2, 3, "0.1000;0.1000;0.5000;0.5000"));

        Browser.Page refused = browser.postMultipart("/problems", fields, List.of(solutionPart()));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("рамка ставится на одну страницу")
                .contains("name=\"conditionFrom\" value=\"1\"")
                .contains("name=\"conditionFrom\" value=\"2\"")
                .contains("name=\"conditionTo\" value=\"3\"")
                .contains("name=\"conditionCrop\" value=\"0.1000;0.1000;0.5000;0.5000\"");
        assertThat(drafts.findByIds(administrator.id(), List.of(book.id()))).hasSize(1);
    }

    @Test
    void emptyOrderIsAMissingFile() {
        TaxonomyNodeId topic = library.topic();

        assertThatThrownBy(() -> problems.create(ExamPart.FIRST, List.of(topic), List.of(library.method()),
                List.of(), new PdfAssemblyOrder(List.of()), new UploadedFile(TestLibrary.pdf(), FileType.PDF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не приложен PDF условия");
    }

    private Browser loggedInBrowser() {
        Browser browser = new Browser(port);
        browser.logIn(administrator.login(), administrator.password());
        return browser;
    }

    private List<Map.Entry<String, String>> markup() {
        return new ArrayList<>(List.of(
                Map.entry("part", "FIRST"),
                Map.entry("topics", String.valueOf(library.topic().value())),
                Map.entry("methodIds", String.valueOf(library.method().value()))));
    }

    /** Поля одной строки сборки условия — черновик, «с», «по» и рамка. */
    private static List<Map.Entry<String, String>> line(AssemblyDraft draft, int from, int to, String crop) {
        return List.of(
                Map.entry("conditionDraft", String.valueOf(draft.id().value())),
                Map.entry("conditionFrom", String.valueOf(from)),
                Map.entry("conditionTo", String.valueOf(to)),
                Map.entry("conditionCrop", crop));
    }

    private static Browser.FilePart solutionPart() {
        return new Browser.FilePart("solution", "solution.pdf", "application/pdf", TestLibrary.pdf());
    }

    private ProblemId create(ProblemPdf condition, ProblemPdf solution) {
        return problems.create(ExamPart.FIRST,
                List.of(library.topic()), List.of(library.method()), List.of(), condition, solution);
    }

    private AssemblyDraft upload(String name, byte[] content) {
        return service.upload(name, new ByteArrayInputStream(content));
    }

    private static PdfAssemblyOrder order(AssemblyDraft... images) {
        return new PdfAssemblyOrder(java.util.Arrays.stream(images)
                .map(draft -> new PdfAssemblyOrder.Line(draft.id(), 1, 1))
                .toList());
    }
}
