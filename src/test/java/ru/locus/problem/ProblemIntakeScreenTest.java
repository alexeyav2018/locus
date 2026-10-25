package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import ru.locus.user.Role;

/**
 * Задача 4.5 problem-intake — признак готовности карточки: страница вычитки
 * показывает Улику рядом с отрисовкой, подсвечивает сомнительное, правит
 * черновик с версией и утверждает его Задачей (design.md, Р4–Р6).
 *
 * Черновик заводит сервис под личностью Администратора — так его заведёт
 * Помощник инструментом; дальше всё идёт запросами браузера тем же
 * Администратором, теми полями, что шлёт форма страницы.
 */
class ProblemIntakeScreenTest extends IntegrationTest {

    private static final String CONDITION = "Найдите $x$, если $\\log_2 ⟦8⟧ = x$.";
    private static final String SOLUTION = "По определению логарифма $x = 3$.";
    private static final CropFrame FRAME = new CropFrame(0.1, 0.1, 0.5, 0.5);
    private static final String CONVERSATION = "/assistant?conversation=7";

    @LocalServerPort
    private int port;

    @Autowired
    private ProblemDraftService service;

    @Autowired
    private ProblemDraftRepository repository;

    @Autowired
    private AssemblyDraftService assemblyDrafts;

    @Autowired
    private ProblemService problems;

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
    void cleanUp() {
        repository.deleteAll();
        LoggedIn.nobody();
    }

    /** Сценарий «Черновик по двум фото» и отметка «изменён Помощником». */
    @Test
    void draftOfTwoPhotosShowsEvidenceBesideTheRendering() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        Browser.Page page = browser(administrator).get(address(draft));

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Улика условия, страница 1")
                .contains("Улика решения, страница 1")
                .contains("alt=\"Условие, страница 1\"")
                .contains("alt=\"Решение, страница 1\"")
                .contains("изменён Помощником")
                .contains("Мой кабинет")
                .contains("name=\"version\" value=\"" + draft.version() + "\"");
        assertThat(page.body()).as("строки сборки — сценой рамок с прежними именами полей")
                .contains("name=\"conditionCrop\" value=\"" + FRAME.formValue() + "\"")
                .contains("name=\"solutionDraft\"");
    }

    /** Сценарий «Подсветка неразборчивого». */
    @Test
    void doubtfulFragmentIsMarkedAndItsReasonShown() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        String body = browser(administrator).get(address(draft)).body();

        assertThat(body).contains("<mark>8</mark>").contains("8 похоже на 9").contains("Метод интервалов");
    }

    /** Сценарий «Отметка последней правки»: «Сохранить черновик» — версия растёт, «изменён вами». */
    @Test
    void savedDraftIsMarkedAsChangedByYou() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        Browser.Page saved = admin.postForm(address(draft), fields(draft, "Новое условие $x$", true));

        assertThat(saved.redirectsTo(address(draft))).isTrue();
        ProblemDraft written = service.draft(draft.id());
        assertThat(written.version()).isEqualTo(draft.version() + 1);
        assertThat(written.content().conditionText()).isEqualTo("Новое условие $x$");
        assertThat(written.content().doubts()).as("причины на странице не правятся").containsExactly("8 похоже на 9");
        assertThat(admin.get(address(draft)).body()).contains("изменён вами");
    }

    /** Сценарии «Сохранение со старой версией» и «Сохранение с текущей версией». */
    @Test
    void savingWithAStaleVersionIsRefusedAndWithTheCurrentMakesAProblem() throws IOException {
        ProblemDraft read = assistantDraft(photo(), photo());
        assistantDraft(photo(), photo());
        ProblemDraft assistant = service.write(read.id(), read.version(), new ProblemDraftChange(null,
                "Условие Помощника $\\log_2 8 = x$", null, null, null, null, null, null, null, null),
                ProblemDraft.Editor.ASSISTANT);
        Browser admin = browser(administrator);

        Browser.Page stale = admin.postForm(address(read) + "/problem", fields(read, "Условие учителя", true));

        assertThat(stale.status()).isEqualTo(200);
        assertThat(stale.body()).contains("перечитайте").contains("Условие Помощника");
        assertThat(service.draft(read.id())).as("черновик — тот, что записал Помощник").isEqualTo(assistant);

        Browser.Page current = admin.postForm(address(assistant) + "/problem",
                fields(assistant, "Найдите $x$, если $\\log_2 ⟦8⟧ = x$.", true));

        assertThat(current.status()).isEqualTo(302);
        Problem created = problems.problem(created(current));
        assertThat(created.hasText()).isTrue();
        assertThat(created.text().condition()).isEqualTo("Найдите $x$, если $\\log_2 8 = x$.");
        assertThat(created.text().conditionEvidence()).isNotNull();
        assertThat(created.text().solutionEvidence()).isNotNull();
        assertThat(repository.find(administrator.id(), read.id())).as("черновик закрыт").isEmpty();
    }

    /** Сценарий «Снимок как есть». */
    @Test
    void snapshotMakesAProblemWithoutText() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        assistantDraft(photo(), photo());

        Browser.Page saved = browser(administrator).postForm(address(draft) + "/snapshot",
                fields(draft, CONDITION, true));

        assertThat(saved.status()).isEqualTo(302);
        assertThat(problems.problem(created(saved)).hasText()).isFalse();
        assertThat(repository.find(administrator.id(), draft.id())).isEmpty();
    }

    /** Сценарий «Отказ разметкой»: Задачи нет, отказ на странице, правка текста сохранена. */
    @Test
    void refusalWithoutAMethodKeepsTheEditedText() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        Browser.Page refused = browser(administrator).postForm(address(draft) + "/problem",
                fields(draft, "Поправленное условие $x$", false));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("class=\"alert\"").contains("Метод");
        ProblemDraft stored = service.draft(draft.id());
        assertThat(stored.content().conditionText()).isEqualTo("Поправленное условие $x$");
        assertThat(stored.version()).isEqualTo(draft.version() + 1);
        assertThat(refused.body()).contains("name=\"version\" value=\"" + stored.version() + "\"");
    }

    /** Сценарий «Ошибка в формуле черновика»: отказ на месте отрисовки, черновик сохраняется. */
    @Test
    void brokenFormulaIsRefusedInPlaceButTheDraftIsSaved() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        Browser.Page saved = admin.postForm(address(draft), fields(draft, "Формула $\\foo$", true));

        assertThat(saved.status()).isEqualTo(302);
        assertThat(admin.get(address(draft)).body()).contains("\\foo");
    }

    /** Сценарий «Раздел вместо Темы» на странице: отказ, присланное в полях, черновик прежний. */
    @Test
    void sectionInTopicsIsRefusedAndTheSentTextStaysInTheField() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        List<Map.Entry<String, String>> sent = new ArrayList<>(fields(draft, "Набранное учителем", true));
        sent.add(Map.entry("topics", String.valueOf(library.section().value())));

        Browser.Page refused = browser(administrator).postForm(address(draft), sent);

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("class=\"alert\"").contains("Набранное учителем");
        assertThat(service.draft(draft.id())).isEqualTo(draft);
    }

    /** Сценарий «Вторая Задача с того же листа». */
    @Test
    void secondDraftOfTheSamePhotoBecomesASecondProblem() throws IOException {
        AssemblyDraft photo = photo();
        ProblemDraft first = assistantDraft(photo, photo);
        ProblemDraft second = assistantDraft(photo, photo);
        Browser admin = browser(administrator);

        Browser.Page firstSaved = admin.postForm(address(first) + "/problem", fields(first, CONDITION, true));
        assertThat(firstSaved.status()).isEqualTo(302);

        assertThat(admin.get(address(second)).body()).contains("Улика условия, страница 1");
        Browser.Page saved = admin.postForm(address(second) + "/problem", fields(second, CONDITION, true));
        assertThat(saved.status()).isEqualTo(302);
        assertThat(saved.location()).as("черновиков не осталось — к Помощнику").endsWith("/assistant");
        assertThat(problems.problem(created(firstSaved)).hasText()).isTrue();
    }

    /**
     * Уход с несохранённым (intake-unsaved-warning, design.md, решения 2, 5):
     * скрипт следит за формой правки по {@code data-unsaved}; форма удаления
     * не помечена. Прочитанная страница — значение {@code read}: несохранённое
     * скрипт найдёт сравнением полей со снимком.
     */
    @Test
    void editFormIsWatchedForUnsavedChanges() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        String body = browser(administrator).get(address(draft)).body();

        assertThat(count(body, "data-unsaved=")).as("помечена одна форма — правки").isEqualTo(1);
        assertThat(body).contains("data-unsaved=\"read\"");
        assertThat(body.substring(body.indexOf("data-unsaved=")))
                .as("форма удаления идёт после формы правки и не помечена")
                .contains("/delete");
    }

    /** Отказ разметкой (Раздел вместо Темы) показал присланное: страница несохранена сразу, без новой правки. */
    @Test
    void pageRefusedWithTheSentFieldsIsUnsavedAtOnce() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        List<Map.Entry<String, String>> sent = new ArrayList<>(fields(draft, "Набранное учителем", true));
        sent.add(Map.entry("topics", String.valueOf(library.section().value())));

        Browser.Page refused = browser(administrator).postForm(address(draft), sent);

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Набранное учителем").contains("data-unsaved=\"sent\"");
    }

    /**
     * Скрипт спрашивает при уходе по формам {@code data-unsaved}: снимок полей
     * против текущих, {@code sent} — несохранено сразу, отправка снимает вопрос.
     */
    @Test
    void scriptAsksBeforeLeavingWithUnsavedChanges() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/js/locus.js"), StandardCharsets.UTF_8);

        assertThat(script).contains("form[data-unsaved]", "addEventListener('beforeunload'",
                "=== 'sent'", "event.returnValue = ''", "submitted = true");
    }

    @Test
    void deleteRemovesTheDraft() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        Browser.Page deleted = browser(administrator).postForm(address(draft) + "/delete",
                Map.of("version", String.valueOf(draft.version())));

        assertThat(deleted.redirectsTo("/assistant")).as("черновиков не осталось").isTrue();
        assertThat(repository.find(administrator.id(), draft.id())).isEmpty();
    }

    /** Сценарий «Сохранение черновика не теряет возврата» (back-link-gaps, Р6). */
    @Test
    void writingKeepsTheReturn() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        Browser.Page saved = admin.postForm(address(draft) + "?from=" + encoded(CONVERSATION),
                fields(draft, CONDITION, true));

        assertThat(saved.redirectsTo(address(draft) + "?from=" + encoded(CONVERSATION))).isTrue();
        assertThat(admin.get(relative(saved)).body()).contains("href=\"" + CONVERSATION + "\">← ");
    }

    /** Сценарий «Отказ разметкой»: «назад» ведёт туда же, куда до нажатия. */
    @Test
    void refusalKeepsTheReturn() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        Browser.Page refused = browser(administrator).postForm(
                address(draft) + "/problem?from=" + encoded(CONVERSATION), fields(draft, CONDITION, false));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("class=\"alert\"").contains("href=\"" + CONVERSATION + "\">← ");
    }

    /** Сценарий «Сохранение ведёт к оставшимся черновикам» (Р4, Р5). */
    @Test
    void savingLeadsToTheRemainingDraftsWithTheCreatedProblem() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        Browser.Page saved = admin.postForm(address(draft) + "/problem?from=" + encoded(CONVERSATION),
                fields(draft, CONDITION, true));

        ProblemId created = created(saved);
        assertThat(saved.location()).contains("/problems/intake?created=" + created.value()
                + "&from=" + encoded(CONVERSATION));
        String list = admin.get(relative(saved)).body();
        assertThat(list).contains("Заведена Задача № " + created.value())
                .contains("href=\"/problems/" + created.value() + "?from=")
                .contains("href=\"" + CONVERSATION + "\">← К Помощнику");
    }

    /** Черновик, открытый из списка: список не вкладывается сам в себя, прежний {@code created} заменён. */
    @Test
    void draftOpenedFromTheListLeadsBackToTheSameList() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        assistantDraft(photo(), photo());
        String list = "/problems/intake?created=999&from=" + encoded(CONVERSATION);

        Browser.Page saved = browser(administrator).postForm(address(draft) + "/problem?from=" + encoded(list),
                fields(draft, CONDITION, true));

        assertThat(saved.location()).endsWith("/problems/intake?created=" + created(saved).value()
                + "&from=" + encoded(CONVERSATION));
    }

    /** Сценарий «Последний черновик возвращает в Разговор»: и прямо, и через список. */
    @Test
    void lastDraftLeadsToTheConversation() throws IOException {
        ProblemDraft direct = assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        Browser.Page saved = admin.postForm(address(direct) + "/snapshot?from=" + encoded(CONVERSATION),
                fields(direct, CONDITION, true));
        assertThat(saved.redirectsTo(CONVERSATION)).isTrue();

        ProblemDraft listed = assistantDraft(photo(), photo());
        String list = "/problems/intake?from=" + encoded(CONVERSATION);
        Browser.Page viaList = admin.postForm(address(listed) + "/snapshot?from=" + encoded(list),
                fields(listed, CONDITION, true));
        assertThat(viaList.redirectsTo(CONVERSATION)).isTrue();
    }

    /** Без возврата последний черновик ведёт на раздел по умолчанию ссылки «назад» списка. */
    @Test
    void lastDraftWithoutAReturnLeadsToTheAssistant() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());

        Browser.Page saved = browser(administrator).postForm(address(draft) + "/problem",
                fields(draft, CONDITION, true));

        assertThat(saved.redirectsTo("/assistant")).isTrue();
    }

    /** Удаление при оставшихся черновиках — список с возвратом, без надписи. */
    @Test
    void deleteLeadsToTheRemainingDraftsWithoutANotice() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        String form = admin.get(address(draft) + "?from=" + encoded(CONVERSATION)).body();
        assertThat(form).as("форма удаления несёт возврат").contains("/delete?from=/assistant?conversation%3D7");

        Browser.Page deleted = admin.postForm(address(draft) + "/delete?from=" + encoded(CONVERSATION),
                Map.of("version", String.valueOf(draft.version())));

        assertThat(deleted.redirectsTo("/problems/intake?from=" + encoded(CONVERSATION))).isTrue();
        assertThat(admin.get(relative(deleted)).body()).doesNotContain("Заведена Задача");
    }

    /** Надпись — подсказка: несуществующая Задача не даёт ни надписи, ни отказа (Р5). */
    @Test
    void createdWithoutSuchProblemShowsNothing() throws IOException {
        Browser.Page list = browser(administrator).get("/problems/intake?created=999999");

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).doesNotContain("Заведена Задача");
    }

    /** Сценарий «Чужой черновик — 404». */
    @Test
    void someoneElsesDraftIsNotFound() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        Browser other = browser(accounts.settled(Role.ADMINISTRATOR));

        assertThat(other.get(address(draft)).status()).isEqualTo(404);
        assertThat(other.postForm(address(draft), fields(draft, "Чужое", true)).status()).isEqualTo(404);
        assertThat(other.get("/problems/intake").body()).doesNotContain("draft-" + draft.id().value());
    }

    /** Сценарий «Учитель без роли Администратора». */
    @Test
    void teacherWithoutTheAdministratorRoleIsForbidden() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        Browser teacher = browser(accounts.settled(Role.TEACHER));

        assertThat(teacher.get("/problems/intake").status()).isEqualTo(403);
        assertThat(teacher.get(address(draft)).status()).isEqualTo(403);
        assertThat(teacher.get("/assistant").body()).doesNotContain("Вычитать черновики");
    }

    @Test
    void listShowsOwnDraftsAndTheAssistantPageLinksToThem() throws IOException {
        ProblemDraft draft = assistantDraft(photo(), photo());
        Browser admin = browser(administrator);

        assertThat(admin.get("/problems/intake").body())
                .contains("draft-" + draft.id().value())
                .contains("Найдите $x$, если $\\log_2 8 = x$.");
        assertThat(admin.get("/assistant").body()).contains("Вычитать черновики: 1");
    }

    /** Номер заведённой Задачи из переадресации на список черновиков. */
    private static ProblemId created(Browser.Page page) {
        java.util.regex.Matcher number = Pattern.compile("created=(\\d+)").matcher(page.location());
        assertThat(number.find()).as("переадресация называет заведённую Задачу: " + page.location()).isTrue();
        return new ProblemId(Long.parseLong(number.group(1)));
    }

    private static String encoded(String address) {
        return java.net.URLEncoder.encode(address, StandardCharsets.UTF_8);
    }

    private static String relative(Browser.Page page) {
        return page.location().replaceFirst("^https?://[^/]+", "");
    }

    private static int count(String body, String text) {
        return body.split(Pattern.quote(text), -1).length - 1;
    }

    private ProblemDraft assistantDraft(AssemblyDraft conditionPhoto, AssemblyDraft solutionPhoto) {
        return service.create(new ProblemDraftContent(ExamPart.SECOND, CONDITION, SOLUTION,
                framed(conditionPhoto), framed(solutionPhoto), List.of("8 похоже на 9"),
                List.of(library.topic()), List.of(library.method()), List.of(), List.of("Метод интервалов")),
                ProblemDraft.Editor.ASSISTANT);
    }

    private static PdfAssemblyOrder framed(AssemblyDraft photo) {
        return new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(photo.id(), 1, 1, FRAME)));
    }

    /** Поля формы вычитки, как их шлёт браузер: хранимое, с заменённым условием. */
    private static List<Map.Entry<String, String>> fields(ProblemDraft draft, String condition, boolean withMethods) {
        ProblemDraftContent content = draft.content();
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("version", String.valueOf(draft.version())));
        fields.add(Map.entry("part", content.part().name()));
        content.topics().forEach(topic -> fields.add(Map.entry("topics", String.valueOf(topic.value()))));
        if (withMethods) {
            content.methods().forEach(method -> fields.add(Map.entry("methodIds", String.valueOf(method.value()))));
        }
        fields.add(Map.entry("conditionText", condition));
        fields.add(Map.entry("solutionText", content.solutionText()));
        lines(fields, "condition", content.condition());
        lines(fields, "solution", content.solution());
        return fields;
    }

    private static void lines(List<Map.Entry<String, String>> fields, String slot, PdfAssemblyOrder order) {
        for (PdfAssemblyOrder.Line line : order.lines()) {
            fields.add(Map.entry(slot + "Draft", String.valueOf(line.draft().value())));
            fields.add(Map.entry(slot + "From", String.valueOf(line.from())));
            fields.add(Map.entry(slot + "To", String.valueOf(line.to())));
            fields.add(Map.entry(slot + "Crop", line.frame() == null ? "" : line.frame().formValue()));
        }
    }

    private AssemblyDraft photo() throws IOException {
        return assemblyDrafts.upload("лист.png", new ByteArrayInputStream(AssemblyDraftServiceTest.png()));
    }

    private static String address(ProblemDraft draft) {
        return "/problems/intake/" + draft.id().value();
    }

    private Browser browser(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
