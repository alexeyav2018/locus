package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Страницы Задачи с текстом (design.md изменения {@code problem-text},
 * Р4, Р7–Р9): заведение формой, предпросмотр без сохранения, правка слота
 * с версией, обмен файлом {@code .md}, Улика на странице просмотра.
 *
 * Запросы идут настоящим HTTP, как их шлёт браузер без скрипта: предпросмотр
 * обязан работать именно так. Сравнивается видимый текст, а не разметка
 * целиком (antipatterns.md, «Сравнение разметки страницы целиком»).
 */
class ProblemTextPagesTest extends IntegrationTest {

    private static final String CONDITION = "Решите уравнение $\\log_2 (x+1) = 3$.";
    private static final String SOLUTION = "Отсюда $x + 1 = 2^3$, то есть $x = 7$.";

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemService problems;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Заведение формой: вид «с текстом» — PDF слотов отрисованы, текст хранится. */
    @Test
    void problemWithTextIsCreatedByTheForm() {
        TaxonomyNodeId topic = library.topic();

        Browser.Page created = administrator().postMultipart("/problems",
                fields(topic, "TEXT", CONDITION, SOLUTION), List.of());

        assertThat(created.status()).isEqualTo(302);
        List<Problem> onTopic = problems.problemsOf(topic);
        assertThat(onTopic).hasSize(1);
        assertThat(onTopic.get(0).text().condition()).isEqualTo(CONDITION);
    }

    /**
     * Сценарий «Формула видна в предпросмотре»: картинка на странице формы,
     * введённое осталось, Задача не заведена.
     */
    @Test
    void previewShowsThePicturesKeepsTheInputAndCreatesNothing() {
        TaxonomyNodeId topic = library.topic();

        Browser.Page page = administrator().postMultipart("/problems/preview",
                fields(topic, "TEXT", "Половина: $\\frac{1}{2}$.", ""), List.of());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Предпросмотр")
                .contains("data:image/jpeg;base64,")
                .as("введённый текст остался в форме")
                .contains("Половина: $\\frac{1}{2}$.")
                .as("и отмеченная Тема тоже")
                .containsPattern("value=\"" + topic.value() + "\"\\s+selected");
        assertThat(problems.problemsOf(topic)).as("Задача не заведена").isEmpty();
    }

    /** Сценарий «Предпросмотр с ошибкой»: отказ на месте, введённое осталось. */
    @Test
    void previewOfAnUnparseableFormulaShowsTheRefusalInPlace() {
        TaxonomyNodeId topic = library.topic();

        Browser.Page page = administrator().postMultipart("/problems/preview",
                fields(topic, "TEXT", "Плохо: $\\foo$.", SOLUTION), List.of());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("\\foo")
                .contains("Плохо: $\\foo$.")
                .doesNotContain("data:image/jpeg;base64,");
        assertThat(problems.problemsOf(topic)).isEmpty();
    }

    /** Сценарий «Скачан текст условия»: файл {@code .md} с именем, называющим номер и слот, в UTF-8. */
    @Test
    void conditionIsDownloadedAsMarkdownNamedByNumberAndSlot() {
        ProblemId id = withText();

        Browser.Page file = administrator().get("/problems/" + id.value() + "/condition.md");

        assertThat(file.status()).isEqualTo(200);
        assertThat(file.contentType()).startsWith("text/markdown").containsIgnoringCase("charset=UTF-8");
        assertThat(file.body()).isEqualTo(CONDITION);
        assertThat(file.disposition()).get().asString()
                .startsWith("attachment")
                .contains("filename*=UTF-8''")
                .contains(java.net.URLEncoder.encode("Задача " + id.value(), StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .contains(java.net.URLEncoder.encode("условие.md", StandardCharsets.UTF_8));
    }

    /** У Задачи без текста скачивать нечего. */
    @Test
    void textlessProblemHasNoMarkdown() {
        ProblemId id = library.problem(library.topic());

        assertThat(administrator().get("/problems/" + id.value() + "/solution.md").status()).isEqualTo(404);
    }

    /**
     * Сценарий «Загружен поправленный текст»: файл заменяет текст своего
     * слота, метка порядка байтов снята, второй слот не тронут.
     */
    @Test
    void uploadedMarkdownReplacesTheTextOfItsSlot() {
        ProblemId id = withText();
        String corrected = "Решите уравнение $\\log_3 (x+1) = 2$.";
        byte[] withBom = ("﻿" + corrected).getBytes(StandardCharsets.UTF_8);

        Browser.Page uploaded = administrator().postMultipart("/problems/" + id.value() + "/condition.md",
                Map.of("version", "0"),
                List.of(new Browser.FilePart("file", "Задача — условие.md", "text/markdown", withBom)));

        assertThat(uploaded.status()).isEqualTo(302);
        ProblemText text = problems.problem(id).text();
        assertThat(text.condition()).isEqualTo(corrected);
        assertThat(text.solution()).isEqualTo(SOLUTION);
        assertThat(text.version()).isEqualTo(1);
    }

    /** Сценарий «Загрузка со старой версией»: отказ «перечитайте», текст прежний. */
    @Test
    void uploadWithAStaleVersionIsRefused() {
        ProblemId id = withText();
        problems.editText(id, ProblemSlot.SOLUTION, "Ответ: $x = 7$.", 0);

        Browser.Page refused = administrator().postMultipart("/problems/" + id.value() + "/condition.md",
                Map.of("version", "0"),
                List.of(new Browser.FilePart("file", "условие.md", "text/markdown",
                        "Другое условие.".getBytes(StandardCharsets.UTF_8))));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("перечитайте");
        assertThat(problems.problem(id).text().condition()).isEqualTo(CONDITION);
    }

    /** Файл не в UTF-8 не перекодируется наугад, а отклоняется. */
    @Test
    void markdownNotInUtf8IsRefused() {
        ProblemId id = withText();

        Browser.Page refused = administrator().postMultipart("/problems/" + id.value() + "/condition.md",
                Map.of("version", "0"),
                List.of(new Browser.FilePart("file", "условие.md", "text/markdown",
                        "Условие".getBytes(java.nio.charset.Charset.forName("windows-1251")))));

        assertThat(refused.body()).contains("UTF-8");
        assertThat(problems.problem(id).text().condition()).isEqualTo(CONDITION);
    }

    /**
     * Сценарий «Текст изменился после открытия страницы» — правкой в форме:
     * отказ, и введённое осталось в поле, чтобы его не набирать заново.
     */
    @Test
    void editWithAStaleVersionIsRefusedAndKeepsTheInput() {
        ProblemId id = withText();
        problems.editText(id, ProblemSlot.CONDITION, "Новое условие $x$.", 0);

        Browser.Page refused = administrator().postForm("/problems/" + id.value() + "/solution/text",
                Map.of("solutionText", "Моё решение $y$.", "version", "0"));

        assertThat(refused.body()).contains("перечитайте").contains("Моё решение $y$.");
        assertThat(problems.problem(id).text().solution()).isEqualTo(SOLUTION);
    }

    /** Правка слота формой перерисовывает только его. */
    @Test
    void solutionIsEditedByTheForm() {
        ProblemId id = withText();
        Problem before = problems.problem(id);

        Browser.Page saved = administrator().postForm("/problems/" + id.value() + "/solution/text",
                Map.of("solutionText", "Ответ: $x = 7$.", "version", "0"));

        assertThat(saved.status()).isEqualTo(302);
        Problem after = problems.problem(id);
        assertThat(after.text().solution()).isEqualTo("Ответ: $x = 7$.");
        assertThat(after.conditionFile()).isEqualTo(before.conditionFile());
        assertThat(after.solutionFile()).isNotEqualTo(before.solutionFile());
    }

    /** Предпросмотр на странице правки показывает присланный текст и ничего не меняет. */
    @Test
    void previewOnTheEditPageChangesNothing() {
        ProblemId id = withText();

        Browser.Page page = administrator().postForm("/problems/" + id.value() + "/text/preview",
                Map.of("conditionText", "Черновик $\\sqrt{2}$.", "version", "0"));

        assertThat(page.body()).contains("data:image/jpeg;base64,").contains("Черновик $\\sqrt{2}$.");
        assertThat(problems.problem(id).text().condition()).isEqualTo(CONDITION);
        assertThat(problems.problem(id).text().version()).isZero();
    }

    /** Сценарий «Текст записан» — формой «Записать текст» на странице правки Задачи без текста. */
    @Test
    void textIsWrittenToATextlessProblemByTheForm() {
        ProblemId id = library.problem(library.topic());
        Problem before = problems.problem(id);

        Browser.Page written = administrator().postForm("/problems/" + id.value() + "/text",
                Map.of("conditionText", CONDITION, "solutionText", SOLUTION));

        assertThat(written.status()).isEqualTo(302);
        Problem after = problems.problem(id);
        assertThat(after.hasText()).isTrue();
        assertThat(after.text().conditionEvidence()).isEqualTo(before.conditionFile());
    }

    /**
     * Сценарий «Задача с Уликой условия»: страница правки ведёт к Улике,
     * а та открывается страницей просмотра с картинками страниц.
     */
    @Test
    void evidenceOpensAsAViewerPage() {
        ProblemId id = problems.create(ExamPart.SECOND, List.of(library.topic()), List.of(library.method()),
                List.of(), CONDITION, SOLUTION,
                new UploadedFile(TestLibrary.renderablePdf(1), FileType.PDF), null);
        Browser browser = administrator();

        String edit = browser.get("/problems/" + id.value() + "/edit").body();
        Browser.Page viewer = browser.get("/problems/" + id.value() + "/condition/evidence");
        Browser.Page picture = browser.get("/problems/" + id.value() + "/condition/evidence/pages/1");

        assertThat(edit).contains("Улика условия").contains("У решения Улики нет.");
        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.body()).contains("Улика: условие");
        assertThat(picture.status()).isEqualTo(200);
        assertThat(picture.contentType()).isEqualTo("image/jpeg");
        assertThat(browser.get("/problems/" + id.value() + "/solution/evidence").status())
                .as("Улики решения нет — нечего и показывать").isEqualTo(404);
    }

    /** У Задачи с текстом PDF — отрисовка: замена файла слота отклоняется. */
    @Test
    void renderedPdfIsNotReplacedByAFile() {
        ProblemId id = withText();
        Problem before = problems.problem(id);

        Browser.Page refused = administrator().postMultipart("/problems/" + id.value() + "/condition",
                Map.of(), Map.of("condition", TestLibrary.renderablePdf(1)));

        assertThat(refused.body()).contains("отрисован из её текста");
        assertThat(problems.problem(id).conditionFile()).isEqualTo(before.conditionFile());
    }

    private ProblemId withText() {
        return problems.create(ExamPart.SECOND, List.of(library.topic()), List.of(library.method()),
                List.of(), CONDITION, SOLUTION, null, null);
    }

    private Map<String, String> fields(TaxonomyNodeId topic, String kind, String condition, String solution) {
        return Map.of(
                "part", ExamPart.SECOND.name(),
                "topics", String.valueOf(topic.value()),
                "methodIds", String.valueOf(library.method().value()),
                "kind", kind,
                "conditionText", condition,
                "solutionText", solution);
    }

    private Browser administrator() {
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
