package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Уход с формы Задачи с несохранённым (design.md изменения
 * {@code problem-form-unsaved-warning}, решения 2–4): какие формы страницы
 * помечены {@code data-unsaved} и какая из них после перерисовки показывает
 * присланное — {@code sent}. Скрипт тот же, что у вычитки, и проверяется
 * в {@code ProblemIntakeScreenTest}.
 *
 * Формы различаются окончанием своего действия: значение атрибута читается
 * у тега формы, а не по странице целиком.
 */
class ProblemFormUnsavedTest extends IntegrationTest {

    private static final String CONDITION = "Решите уравнение $\\log_2 (x+1) = 3$.";
    private static final String SOLUTION = "Отсюда $x + 1 = 2^3$, то есть $x = 7$.";

    private static final Pattern FORM = Pattern.compile("<form\\b[^>]*>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"?]*)");
    private static final Pattern UNSAVED = Pattern.compile("data-unsaved=\"([a-z]+)\"");

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

    /**
     * Сценарий «Открыл новую Задачу с Темы и ушёл, ничего не тронув»: форма
     * прочитана, несохранённое скрипт найдёт только отличием от показанного.
     */
    @Test
    void newProblemFormIsReadAtFirst() {
        TaxonomyNodeId topic = library.topic();

        String body = administrator().get("/problems/new?topic=" + topic.value()).body();

        assertThat(unsaved(body)).containsExactly(Map.entry("/problems", "read"));
    }

    /** Отказ заведения показал присланное: несохранено сразу. */
    @Test
    void refusedCreationIsSent() {
        Map<String, String> sent = new LinkedHashMap<>(fields(library.topic()));
        sent.remove("methodIds");

        Browser.Page refused = administrator().postMultipart("/problems", sent, List.of());

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Метод");
        assertThat(unsaved(refused.body())).containsExactly(Map.entry("/problems", "sent"));
    }

    /** Сценарий «Предпросмотр показал присланное». */
    @Test
    void previewOfANewProblemIsSent() {
        Browser.Page page = administrator().postMultipart("/problems/preview", fields(library.topic()), List.of());

        assertThat(page.body()).contains("data:image/jpeg;base64,");
        assertThat(unsaved(page.body())).containsExactly(Map.entry("/problems", "sent"));
    }

    /**
     * Правка Задачи с текстом: помечены разметка, тексты слотов и замены
     * Улик, а загрузки {@code .md} — нет.
     */
    @Test
    void editPageOfAProblemWithTextIsReadAtFirst() {
        ProblemId id = withText();
        String at = "/problems/" + id.value();

        String body = administrator().get(at + "/edit").body();

        assertThat(unsaved(body)).containsExactly(
                Map.entry(at, "read"),
                Map.entry(at + "/condition/text", "read"),
                Map.entry(at + "/solution/text", "read"),
                Map.entry(at + "/condition/evidence", "read"),
                Map.entry(at + "/solution/evidence", "read"));
        assertThat(body).contains(at + "/condition.md\"");
    }

    /** Правка Задачи без текста: разметка, «Записать текст» и замены файлов. */
    @Test
    void editPageOfATextlessProblemIsReadAtFirst() {
        ProblemId id = library.problem(library.topic());
        String at = "/problems/" + id.value();

        String body = administrator().get(at + "/edit").body();

        assertThat(unsaved(body)).containsExactly(
                Map.entry(at, "read"),
                Map.entry(at + "/text", "read"),
                Map.entry(at + "/condition", "read"),
                Map.entry(at + "/solution", "read"));
    }

    /** Отказ правки разметкой (Раздел вместо Темы) — присланное только у основной формы. */
    @Test
    void refusedMarkupIsSentByTheMainForm() {
        ProblemId id = withText();
        String at = "/problems/" + id.value();
        Map<String, String> sent = new LinkedHashMap<>(fields(library.section()));

        Browser.Page refused = administrator().postForm(at, sent);

        assertThat(refused.status()).isEqualTo(200);
        assertThat(sentForms(refused.body())).containsExactly(at);
    }

    /**
     * Сценарий «Отказ правки текста слота показал присланное»: присланное —
     * у формы решения, форма условия показывает хранимое и прочитана.
     */
    @Test
    void refusedSolutionTextIsSentBySolutionFormOnly() {
        ProblemId id = withText();
        String at = "/problems/" + id.value();
        problems.editText(id, ProblemSlot.CONDITION, "Новое условие $x$.", 0);

        Browser.Page refused = administrator().postForm(at + "/solution/text",
                Map.of("solutionText", "Моё решение $y$.", "version", "0"));

        assertThat(refused.body()).contains("перечитайте");
        assertThat(sentForms(refused.body())).containsExactly(at + "/solution/text");
    }

    /** Предпросмотр текста условия — присланное у формы условия. */
    @Test
    void previewOfTheConditionTextIsSentByConditionForm() {
        ProblemId id = withText();
        String at = "/problems/" + id.value();

        Browser.Page page = administrator().postForm(at + "/text/preview",
                Map.of("conditionText", "Черновик $\\sqrt{2}$.", "version", "0"));

        assertThat(page.body()).contains("data:image/jpeg;base64,");
        assertThat(sentForms(page.body())).containsExactly(at + "/condition/text");
    }

    /** Отказ «Записать текст» неразбираемой формулой — присланное у этой формы. */
    @Test
    void refusedWrittenTextIsSentByItsForm() {
        ProblemId id = library.problem(library.topic());
        String at = "/problems/" + id.value();

        Browser.Page refused = administrator().postForm(at + "/text",
                Map.of("conditionText", "Плохо: $\\foo$.", "solutionText", SOLUTION));

        assertThat(refused.body()).contains("Плохо: $\\foo$.");
        assertThat(sentForms(refused.body())).containsExactly(at + "/text");
    }

    /** Отказ замены файла без файла — присланное у формы замены этого файла. */
    @Test
    void refusedFileReplacementIsSentByItsForm() {
        ProblemId id = library.problem(library.topic());
        String at = "/problems/" + id.value();

        Browser.Page refused = administrator().postMultipart(at + "/solution", Map.of(), List.of());

        assertThat(refused.status()).isEqualTo(200);
        assertThat(sentForms(refused.body())).containsExactly(at + "/solution");
    }

    /** Отказ замены Улики без файла — присланное у формы замены этой Улики. */
    @Test
    void refusedEvidenceReplacementIsSentByItsForm() {
        ProblemId id = withText();
        String at = "/problems/" + id.value();

        Browser.Page refused = administrator().postMultipart(at + "/condition/evidence", Map.of(), List.of());

        assertThat(refused.status()).isEqualTo(200);
        assertThat(sentForms(refused.body())).containsExactly(at + "/condition/evidence");
    }

    /** Отказ загрузки {@code .md} показывает хранимую Задачу: присланного нет ни у одной формы. */
    @Test
    void refusedMarkdownUploadShowsNothingSent() {
        ProblemId id = withText();
        String at = "/problems/" + id.value();
        problems.editText(id, ProblemSlot.CONDITION, "Новое условие $x$.", 0);

        Browser.Page refused = administrator().postMultipart(at + "/condition.md",
                Map.of("version", "0"), Map.of("file", "Моё $x$.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(refused.body()).contains("перечитайте");
        assertThat(sentForms(refused.body())).isEmpty();
    }

    /** Помеченные формы страницы по порядку: окончание действия без запроса → значение атрибута. */
    private static List<Map.Entry<String, String>> unsaved(String body) {
        List<Map.Entry<String, String>> marked = new java.util.ArrayList<>();
        Matcher form = FORM.matcher(body);
        while (form.find()) {
            Matcher value = UNSAVED.matcher(form.group());
            if (value.find()) {
                Matcher action = ACTION.matcher(form.group());
                assertThat(action.find()).as("у помеченной формы есть действие").isTrue();
                marked.add(Map.entry(action.group(1), value.group(1)));
            }
        }
        return marked;
    }

    private static List<String> sentForms(String body) {
        return unsaved(body).stream().filter(entry -> entry.getValue().equals("sent")).map(Map.Entry::getKey).toList();
    }

    private ProblemId withText() {
        return problems.create(ExamPart.SECOND, List.of(library.topic()), List.of(library.method()),
                List.of(), CONDITION, SOLUTION, null, null);
    }

    private Map<String, String> fields(TaxonomyNodeId topic) {
        return Map.of(
                "part", ExamPart.SECOND.name(),
                "topics", String.valueOf(topic.value()),
                "methodIds", String.valueOf(library.method().value()),
                "kind", "TEXT",
                "conditionText", CONDITION,
                "solutionText", SOLUTION);
    }

    private Browser administrator() {
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
