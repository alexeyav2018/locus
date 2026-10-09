package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    private static final Pattern FILE_FIELD = Pattern.compile("<input type=\"file\"[^>]*>");

    private static final Pattern DRAFT = Pattern.compile("name=\"(condition|solution)Draft\" value=\"(\\d+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private ProblemService problems;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private final ListAppender<ILoggingEvent> journal = new ListAppender<>();

    @BeforeEach
    void listenToTheJournal() {
        journal.start();
        ((Logger) LoggerFactory.getLogger(AssemblyRefusals.class)).addAppender(journal);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
        ((Logger) LoggerFactory.getLogger(AssemblyRefusals.class)).detachAppender(journal);
        journal.stop();
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

    /**
     * Неразбираемый PDF: на экране прежний отказ, а в журнале — строка WARN
     * с именем файла и исходной причиной, чтобы разобрать обращение без файла.
     */
    @Test
    void unreadablePdfLeavesItsCauseInTheJournal() {
        byte[] broken = "%PDF-1.7 обрыв".getBytes(StandardCharsets.UTF_8);

        Browser.Page refused = upload(administrator(), "condition", "битый.pdf", broken);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("Файл «битый.pdf» не разбирается как PDF");
        assertThat(journal.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("битый.pdf");
            assertThat(event.getThrowableProxy()).as("исходная причина").isNotNull();
        });
    }

    /** Неразбираемая картинка вскрывается при сборке формой — причина и тогда в журнале. */
    @Test
    void unreadableJpegLeavesItsCauseInTheJournalWhenAssembled() throws IOException {
        Browser admin = administrator();
        List<Map.Entry<String, String>> fields = markup(library.topic(), library.method());
        byte[] broken = {(byte) 0xFF, (byte) 0xD8, 'o', 'b', 'r', 'y', 'v'};
        fields.addAll(rowFields(upload(admin, "condition", "битый.jpg", broken)));

        Browser.Page refused = admin.postMultipart("/problems", fields, List.of(solution()));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Картинка «битый.jpg» не разбирается");
        assertThat(journal.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("битый.jpg");
            assertThat(event.getThrowableProxy()).as("исходная причина").isNotNull();
        });
    }

    /** Неподходящий файл — ответ на ввод, а не сбой: в журнал он не пишется. */
    @Test
    void unsuitableSourceDoesNotReachTheJournal() {
        Browser.Page refused = upload(administrator(), "solution", "заметки.txt", "текст".getBytes());

        assertThat(refused.status()).isEqualTo(422);
        assertThat(journal.list).isEmpty();
    }

    /** Показ страницы своего черновика — картинкой, по ней ставится рамка (ADR-0045). */
    @Test
    void ownDraftPageIsShownAsAPicture() throws IOException {
        Browser admin = administrator();
        String draft = draftOf(upload(admin, "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(3)));

        Browser.Page page = admin.get("/problems/drafts/" + draft + "/pages/2");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.contentType()).startsWith("image/jpeg");
        assertThat(PdfAssembly.isJpeg(admin.getBytes("/problems/drafts/" + draft + "/pages/2"))).isTrue();
    }

    /** Страница вне черновика — тот же 404, что у несуществующего. */
    @Test
    void pageBeyondTheDraftIsNotFound() throws IOException {
        Browser admin = administrator();
        String draft = draftOf(upload(admin, "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(3)));

        assertThat(admin.get("/problems/drafts/" + draft + "/pages/4").status()).isEqualTo(404);
        assertThat(admin.get("/problems/drafts/" + draft + "/pages/0").status()).isEqualTo(404);
        assertThat(admin.get("/problems/drafts/999999/pages/1").status()).isEqualTo(404);
        assertThat(admin.get("/problems/drafts/" + draft + "/pages/4?large=true").status()).isEqualTo(404);
    }

    /** Сценарий «Крупный показ страницы»: A4 вдвое крупнее обычного по длинной стороне. */
    @Test
    void largePageIsTwiceTheUsualSize() throws IOException {
        Browser admin = administrator();
        String draft = draftOf(upload(admin, "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(3)));

        Browser.Page page = admin.get("/problems/drafts/" + draft + "/pages/2?large=true");
        byte[] usual = admin.getBytes("/problems/drafts/" + draft + "/pages/2");
        byte[] large = admin.getBytes("/problems/drafts/" + draft + "/pages/2?large=true");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.contentType()).startsWith("image/jpeg");
        // Отрисовка округляет каждую сторону: «вдвое» — с точностью до пикселя-двух.
        assertThat(longSide(large)).isBetween(2 * longSide(usual) - 2, 2 * longSide(usual) + 2);
    }

    /** Крупный показ чужого черновика — тот же 404, что у несуществующего. */
    @Test
    void largePageOfAnotherAdministratorsDraftIsNotFound() throws IOException {
        String draft = draftOf(upload(administrator(), "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(3)));

        assertThat(administrator().get("/problems/drafts/" + draft + "/pages/1?large=true").status()).isEqualTo(404);
    }

    private static int longSide(byte[] jpeg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        return Math.max(image.getWidth(), image.getHeight());
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

    /** Сценарий «Выбран и не тронут»: строка целиком — в слот ложится сам файл (ADR-0049). */
    @Test
    void untouchedPdfLandsInTheSlotByteForByte() throws IOException {
        Browser admin = administrator();
        byte[] source = AssemblyDraftServiceTest.pdf(4);
        List<Map.Entry<String, String>> fields = markup(library.topic(), library.method());
        fields.addAll(wholeRowFields(upload(admin, "condition", "задача.pdf", source), 4));

        Browser.Page created = admin.postMultipart("/problems", fields, List.of(solution()));

        assertThat(created.status()).as("Задача заведена").isEqualTo(302);
        assertThat(admin.getBytes(problems.conditionLink(createdId(created)).toString())).isEqualTo(source);
    }

    /** Сценарий «Крупный PDF целиком»: держит предел инструмента, а не общий. */
    @Test
    void untouchedPdfAboveTheCommonLimitIsAccepted() throws IOException {
        Browser admin = administrator();
        byte[] source = heavyPdf(21 * 1024 * 1024);
        List<Map.Entry<String, String>> fields = markup(library.topic(), library.method());
        fields.addAll(wholeRowFields(upload(admin, "condition", "крупный.pdf", source), 1));

        Browser.Page created = admin.postMultipart("/problems", fields, List.of(solution()));

        assertThat(created.status()).as("Задача заведена: %s", created.body()).isEqualTo(302);
        assertThat(admin.getBytes(problems.conditionLink(createdId(created)).toString())).isEqualTo(source);
    }

    /** Сценарий «Задача без скрипта»: поле слота уходит готовым PDF, байт в байт. */
    @Test
    void readyPdfWithoutScriptLandsByteForByte() throws IOException {
        Browser admin = administrator();
        byte[] source = AssemblyDraftServiceTest.pdf(2);

        Browser.Page created = admin.postMultipart("/problems", markup(library.topic(), library.method()),
                List.of(new Browser.FilePart("condition", "условие.pdf", "application/pdf", source), solution()));

        assertThat(created.status()).isEqualTo(302);
        assertThat(admin.getBytes(problems.conditionLink(createdId(created)).toString())).isEqualTo(source);
    }

    /** Сценарий «Картинка без скрипта»: в слот PDF она не ложится. */
    @Test
    void readyImageWithoutScriptIsRefused() throws IOException {
        Browser admin = administrator();
        TaxonomyNodeId topic = library.topic();

        Browser.Page refused = admin.postMultipart("/problems", markup(topic, library.method()),
                List.of(new Browser.FilePart("condition", "снимок.png", "image/png", AssemblyDraftServiceTest.png()),
                        solution()));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("PDF условия: без скрипта принимается только PDF");
        assertThat(problems.problemsOf(topic)).isEmpty();
    }

    /**
     * Сценарий «Одно поле на слот» (ADR-0049): и при заведении, и в каждом
     * блоке замены у слота ровно одно поле файла — с именем слота, чтобы
     * без скрипта уйти готовым PDF, и с признаком исходника сборки.
     */
    @Test
    void eachSlotHasOneFileField() {
        Browser admin = administrator();
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        String creation = admin.get("/problems/new?topic=" + topic.value()).body();
        assertThat(fileFieldsOf(creation)).containsExactly("condition", "solution");

        String edit = admin.get("/problems/" + problem.value() + "/edit").body();
        for (String slot : List.of("condition", "solution")) {
            String action = "action=\"/problems/" + problem.value() + "/" + slot + "\"";
            assertThat(edit).as("блок замены %s", slot).contains(action);
            String form = edit.substring(edit.indexOf(action), edit.indexOf("</form>", edit.indexOf(action)));
            assertThat(fileFieldsOf(form)).as("блок замены %s", slot).containsExactly(slot);
        }
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
        assertThat(journal.list).as("ошибка заполнения — не сбой").isEmpty();
    }

    /** Строка несёт пустое поле рамки и адрес страниц черновика для скрипта (ADR-0045). */
    @Test
    void rowCarriesAnEmptyFrameAndThePreviewAddress() throws IOException {
        Browser.Page row = upload(administrator(), "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(3));
        String draft = draftOf(row);

        assertThat(row.body()).contains("name=\"conditionCrop\" value=\"\"")
                .contains("data-preview-url=\"/problems/drafts/" + draft + "/pages/\"")
                .contains("data-assembly=\"crop\"");
    }


    /**
     * Сцену рамки строит скрипт (crop-frame-zoom): панель масштаба, режим
     * «Рисовать | Двигать», восемь ручек и крупная картинка при увеличении;
     * общий файл стилей задаёт им окно с прокруткой и поле касания ручки.
     */
    @Test
    void scriptBuildsTheZoomPanelAndTheHandles() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/js/locus.js"), StandardCharsets.UTF_8);
        String styles = Files.readString(Path.of("src/main/resources/static/css/locus.css"), StandardCharsets.UTF_8);

        assertThat(script).contains("crop-toolbar", "crop-viewport", "crop-handle",
                "data-crop-zoom", "data-crop-mode", "?large=true", "'nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'");
        assertThat(styles).contains(".crop-viewport", ".crop-handle", ".crop-canvas.crop-moving { touch-action: pan-x pan-y");
    }

    /** Отказ формы возвращает рамку в поле: скрипт нарисует её заново. */
    @Test
    void refusedFormKeepsTheFrame() throws IOException {
        Browser admin = administrator();
        List<Map.Entry<String, String>> fields = new ArrayList<>(List.of(
                Map.entry("part", "FIRST"), Map.entry("topics", String.valueOf(library.topic().value()))));
        String draft = draftOf(upload(admin, "condition", "сборник.pdf", AssemblyDraftServiceTest.pdf(5)));
        fields.add(Map.entry("conditionDraft", draft));
        fields.add(Map.entry("conditionFrom", "2"));
        fields.add(Map.entry("conditionTo", "2"));
        fields.add(Map.entry("conditionCrop", "0.1;0.2;0.5;0.3"));

        Browser.Page refused = admin.postMultipart("/problems", fields, List.of(solution()));

        assertThat(refused.body()).contains("хотя бы один Метод")
                .contains("name=\"conditionCrop\" value=\"0.1000;0.2000;0.5000;0.3000\"");
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

    /** Поля нетронутой строки PDF: все страницы, рамки нет — как её рисует загрузка. */
    private static List<Map.Entry<String, String>> wholeRowFields(Browser.Page row, int pages) {
        String slot = slotOf(row);
        return new ArrayList<>(List.of(
                Map.entry(slot + "Draft", draftOf(row)),
                Map.entry(slot + "Crop", ""),
                Map.entry(slot + "From", "1"),
                Map.entry(slot + "To", String.valueOf(pages))));
    }

    private static ProblemId createdId(Browser.Page created) {
        return new ProblemId(Long.parseLong(created.location().replaceAll(".*/", "")));
    }

    /** Одностраничный PDF заданного веса: содержимое страницы — длинный комментарий. */
    private static byte[] heavyPdf(int bytes) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            byte[] filler = new byte[bytes];
            Arrays.fill(filler, (byte) 'x');
            filler[0] = '%';
            filler[bytes - 1] = '\n';
            page.setContents(new PDStream(document, new ByteArrayInputStream(filler)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Слоты полей файла на странице по порядку; поле без имени слота или без
     * признака исходника сборки, либо с расходящимися ими, валит тест.
     */
    private static List<String> fileFieldsOf(String html) {
        List<String> slots = new ArrayList<>();
        Matcher field = FILE_FIELD.matcher(html);
        while (field.find()) {
            Matcher name = Pattern.compile(" name=\"(\\w+)\"").matcher(field.group());
            Matcher source = Pattern.compile("data-assembly-source=\"(\\w+)\"").matcher(field.group());
            assertThat(name.find()).as("у поля есть имя: %s", field.group()).isTrue();
            assertThat(source.find()).as("поле — исходник сборки: %s", field.group()).isTrue();
            assertThat(name.group(1)).as("имя — слот исходника: %s", field.group()).isEqualTo(source.group(1));
            slots.add(name.group(1));
        }
        return slots;
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
