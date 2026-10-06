package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Задачи 3.2–3.3: сборка PDF слота через форму — загрузка исходника
 * отдаёт строку сборки, форма принимает строки наравне с готовым файлом,
 * отказ формы рисует строки заново (ADR-0044).
 *
 * Запросы — те же, что шлёт скрипт формы и сама форма: скрипт лишь
 * вставляет полученную строку в список, поля строки уходят обычной
 * отправкой.
 */
class AssemblyScreenTest extends IntegrationTest {

    private static final Pattern DRAFT = Pattern.compile("name=\"(condition|solution)Draft\" value=\"(\\d+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private ProblemService problems;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Загрузка сборника»: строка называет исходник и число страниц. */
    @Test
    void uploadedPdfComesBackAsARowWithItsName() throws IOException {
        Browser.Page row = upload(administrator(), "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(5));

        assertThat(row.status()).isEqualTo(200);
        assertThat(row.body()).contains("сборник.pdf").contains("страниц: 5")
                .contains("name=\"conditionFrom\"").contains("value=\"5\"");
    }

    /** Сценарий «Учитель загружает исходник». */
    @Test
    void teacherIsRefusedTheUpload() throws IOException {
        Browser teacher = loggedIn(Role.TEACHER);

        assertThat(upload(teacher, "condition", "снимок.png", AssemblyDraftServiceTest.png()).status())
                .isEqualTo(403);
    }

    /** Сценарий «Неподходящий исходник»: отказ — текстом, который покажет скрипт. */
    @Test
    void unsuitableSourceIsRefusedWithAnExplanation() {
        Browser.Page refused = upload(administrator(), "solution", "заметки.txt", "текст".getBytes());

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("JPEG и PNG и файлы PDF");
    }

    /** Сценарий «Задача из трёх картинок» — через форму. */
    @Test
    void problemIsCreatedWithAnAssembledCondition() throws IOException {
        Browser admin = administrator();
        List<Map.Entry<String, String>> fields = markup(library.topic(), library.method());
        for (String name : List.of("1.png", "2.png", "3.png")) {
            fields.addAll(rowFields(upload(admin, "condition", name, AssemblyDraftServiceTest.png())));
        }

        Browser.Page created = admin.postMultipart("/problems", fields, List.of(solution()));

        assertThat(created.status()).as("Задача заведена").isEqualTo(302);
        ProblemId id = new ProblemId(Long.parseLong(created.location().replaceAll(".*/", "")));
        try (PDDocument condition = Loader.loadPDF(admin.getBytes(problems.conditionLink(id).toString()))) {
            assertThat(condition.getNumberOfPages()).isEqualTo(3);
        }
    }

    /** Сценарий «Готовый PDF и сборка на одном слоте». */
    @Test
    void readyFileAndAssemblyOnOneSlotAreRefused() throws IOException {
        Browser admin = administrator();
        TaxonomyNodeId topic = library.topic();
        List<Map.Entry<String, String>> fields = markup(topic, library.method());
        fields.addAll(rowFields(upload(admin, "solution", "снимок.png", AssemblyDraftServiceTest.png())));

        Browser.Page refused = admin.postMultipart("/problems", fields,
                List.of(condition(), solution()));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("выберите один способ");
        assertThat(problems.problemsOf(topic)).isEmpty();
    }

    /** Сценарий «Отказ формы не теряет черновики»: строки нарисованы заново. */
    @Test
    void refusedFormShowsTheSameAssemblyRows() throws IOException {
        Browser admin = administrator();
        List<Map.Entry<String, String>> fields = new ArrayList<>(List.of(
                Map.entry("part", "FIRST"), Map.entry("topics", String.valueOf(library.topic().value()))));
        Browser.Page row = upload(admin, "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(5));
        String draft = draftOf(row);
        fields.add(Map.entry("conditionDraft", draft));
        fields.add(Map.entry("conditionFrom", "2"));
        fields.add(Map.entry("conditionTo", "4"));

        Browser.Page refused = admin.postMultipart("/problems", fields, List.of(solution()));

        assertThat(refused.body()).contains("хотя бы один Метод")
                .contains("сборник.pdf")
                .contains("name=\"conditionDraft\" value=\"" + draft + "\"")
                .contains("name=\"conditionFrom\" value=\"2\"")
                .contains("name=\"conditionTo\" value=\"4\"");
    }

    private Browser.Page upload(Browser browser, String slot, String name, byte[] content) {
        return browser.postMultipart("/problems/drafts", Map.of("slot", slot),
                List.of(new Browser.FilePart("source", name, "application/octet-stream", content)));
    }

    /** Поля строки, как их отправит форма: черновик, «с», «по» — по значению атрибута. */
    private static List<Map.Entry<String, String>> rowFields(Browser.Page row) {
        String slot = slotOf(row);
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry(slot + "Draft", draftOf(row)));
        fields.add(Map.entry(slot + "From", "1"));
        fields.add(Map.entry(slot + "To", "1"));
        return fields;
    }

    private static String draftOf(Browser.Page row) {
        return matched(row).group(2);
    }

    private static String slotOf(Browser.Page row) {
        return matched(row).group(1);
    }

    private static Matcher matched(Browser.Page row) {
        Matcher matcher = DRAFT.matcher(row.body());
        assertThat(matcher.find()).as("в ответе есть строка сборки: %s", row.body()).isTrue();
        return matcher;
    }

    private static List<Map.Entry<String, String>> markup(TaxonomyNodeId topic, SolutionMethodId method) {
        return new ArrayList<>(List.of(
                Map.entry("part", "FIRST"),
                Map.entry("topics", String.valueOf(topic.value())),
                Map.entry("methodIds", String.valueOf(method.value()))));
    }

    private static Browser.FilePart condition() {
        return new Browser.FilePart("condition", "condition.pdf", "application/pdf", TestLibrary.pdf());
    }

    private static Browser.FilePart solution() {
        return new Browser.FilePart("solution", "solution.pdf", "application/pdf", TestLibrary.pdf());
    }

    private Browser administrator() {
        return loggedIn(Role.ADMINISTRATOR);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
