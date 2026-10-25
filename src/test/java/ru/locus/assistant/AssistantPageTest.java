package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import ru.locus.Addresses;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.problem.AssemblyDraft;
import ru.locus.problem.AssemblyDraftService;
import ru.locus.user.Role;

/**
 * Страница разговора с Помощником (спека {@code assistant-chat}; design.md
 * assistant-chat, Р1–Р11) — настоящим HTTP, с тест-двойником команды,
 * отвечающим по первому слову запроса. Реплика идёт параллельно тесту,
 * поэтому конец её тест ждёт, перечитывая страницу, как это делает
 * {@code meta refresh}.
 *
 * <p>С командой по несуществующему пути — свой контекст Spring, в котором
 * Локус всё равно поднимается, а страница называет причину.
 */
class AssistantPageTest extends IntegrationTest {

    /** Сколько ждать конца Реплики: предел времени, запас на SIGTERM и на медленную машину. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private static final String REFRESH = "http-equiv=\"refresh\"";
    private static final String FORM = "name=\"prompt\"";
    private static final String TURNS = Addresses.ASSISTANT + "/turns";
    private static final Pattern TURN_ADDRESS = Pattern.compile("(/assistant\\?conversation=(\\d+))#turn-(\\d+)$");

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;
    @Autowired
    private AssemblyDraftService drafts;

    @Value("${locus.agent.version}")
    private String pinned;

    @AfterEach
    void logout() {
        LoggedIn.nobody();
    }

    /** Сценарий «Всё на месте» и «Пустое состояние». */
    @Test
    void teacherSeesThatTheAssistantIsAvailableAndWhatToWrite() {
        Browser.Page page = loggedIn(port, accounts.settled(Role.TEACHER)).get(Addresses.ASSISTANT);

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Помощник доступен: Claude Code " + pinned)
                .doesNotContain("Помощник недоступен")
                .contains("Напишите, что сделать")
                .contains(FORM)
                .doesNotContain(REFRESH);
    }

    @Test
    void visitorWithoutLoginIsSentToTheLoginForm() {
        Browser.Page page = new Browser(port).get(Addresses.ASSISTANT);

        assertThat(page.redirectsTo(Addresses.LOGIN)).isTrue();
    }

    /** Сценарий «Пункт шапки»: «Кабинет» с «Помощником» видят обе роли (Р9). */
    @Test
    void headerHasTheAssistantItemForTeacherAndAdministrator() {
        for (Role role : List.of(Role.TEACHER, Role.ADMINISTRATOR)) {
            String home = loggedIn(port, accounts.settled(role)).get(Addresses.HOME).body();

            assertThat(home).as(role.name()).contains("href=\"" + Addresses.ASSISTANT + "\"");
        }
    }

    /** Сценарии «Первая реплика», «Завершилась» и «Тот же день». */
    @Test
    void sentPromptLeadsToItsConversationAndEndsWithAnswerAndCost() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

        Turn turn = send(browser, "ответ на вопрос");
        String done = finished(browser, turn);

        assertThat(done).contains("ответ на вопрос")
                .contains("готово")
                .contains("пользователь спайка agent-spike")
                .contains("токенов входа")
                .contains("$0.0014")
                .contains(FORM)
                .contains("action=\"" + TURNS + "?conversation=" + turn.conversation() + "\"");
        assertThat(browser.get(Addresses.ASSISTANT).body()).contains("id=\"turn-" + turn.id() + "\"");
    }

    /** Сценарий «Реплика в том же Разговоре». */
    @Test
    void secondPromptStaysInTheShownConversation() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));
        Turn first = send(browser, "ответ первый");
        finished(browser, first);

        Turn second = send(browser, "ответ второй", first.conversation());
        String page = finished(browser, second);

        assertThat(second.conversation()).isEqualTo(first.conversation());
        assertThat(page.indexOf("id=\"turn-" + first.id() + "\""))
                .isLessThan(page.indexOf("id=\"turn-" + second.id() + "\""));
    }

    /** Сценарий «Готовая Реплика»: два вызова по порядку, имена без {@code mcp__locus__}. */
    @Test
    void callsAreShownInOrder() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

        String page = finished(browser, send(browser, "вызовы"));

        assertThat(page).contains("готово").contains("Ход · вызовов: 2").doesNotContain("mcp__locus__");
        assertThat(page.indexOf(">list_topics<")).isPositive().isLessThan(page.indexOf(">list_methods<"));
    }

    /** Сценарий «Прервано по шагам». */
    @Test
    void stepLimitSuggestsToContinue() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

        String page = finished(browser, send(browser, "шаги"));

        assertThat(page).contains("прервано по шагам").contains("Напишите «продолжай»");
    }

    /** Сценарий «Неудача». */
    @Test
    void failureNamesTheCause() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

        String page = finished(browser, send(browser, "падение"));

        assertThat(page).contains("не удалось").contains("процесс Помощника вышел с кодом 3");
    }

    /**
     * Сценарии «Пока идёт», «Реплика в другом Разговоре» и «Остановка
     * со страницы».
     */
    @Test
    void runningTurnRefreshesItselfAndIsStoppedFromThePage() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));
        Turn earlier = send(browser, "ответ в прежнем разговоре");
        finished(browser, earlier);

        assertThat(browser.get(Addresses.ASSISTANT + "?new=true").body())
                .contains(FORM).contains("action=\"" + TURNS + "\"");
        Turn running = send(browser, "зависание");
        assertThat(running.conversation()).isNotEqualTo(earlier.conversation());

        String page = browser.get(running.page()).body();
        assertThat(page).contains(REFRESH).contains("работаю").contains("Остановить").doesNotContain(FORM);

        String other = browser.get(earlier.page()).body();
        assertThat(other).doesNotContain(REFRESH).doesNotContain(FORM)
                .contains("Помощник отвечает в")
                .contains("href=\"" + running.page() + "#turn-" + running.id() + "\"");

        Browser.Page stopped = browser.postForm(TURNS + "/" + running.id() + "/stop", Map.of());
        assertThat(stopped.location()).endsWith(running.page() + "#turn-" + running.id());
        assertThat(finished(browser, running)).contains("остановлено").contains(FORM);
    }

    /** Сценарии «Внутренняя ссылка», «Внешняя ссылка». */
    @Test
    void answerLinksLeadOnlyInsideWithReturnToTheConversation() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));
        Turn turn = send(browser, "ссылки");

        String page = finished(browser, turn);

        assertThat(page).contains("<a href=\"/problems?from=%2Fassistant%3Fconversation%3D"
                        + turn.conversation() + "\">Задачи</a>")
                .contains("сайт (https://example.com)")
                .doesNotContain("href=\"https://example.com");
    }

    /** Сценарии «Чужой Разговор по адресу» и «Список прежних». */
    @Test
    void anotherTeachersConversationIsNotFound() {
        Browser alice = loggedIn(port, accounts.settled(Role.TEACHER));
        Turn alices = send(alice, "ответ Алисе");
        finished(alice, alices);
        Browser bob = loggedIn(port, accounts.settled(Role.TEACHER));

        assertThat(bob.get(alices.page()).status()).isEqualTo(404);
        assertThat(bob.postForm(TURNS + "/" + alices.id() + "/stop", Map.of()).status()).isEqualTo(404);
        assertThat(bob.get(Addresses.ASSISTANT).body()).doesNotContain("ответ Алисе")
                .doesNotContain("Прежние разговоры");
    }

    /** Сценарии «Прежний Разговор» и «Новый разговор». */
    @Test
    void earlierConversationsAreLinks() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));
        Turn first = send(browser, "ответ о дробях");
        finished(browser, first);

        Browser.Page fresh = browser.get(Addresses.ASSISTANT + "?new=true");
        assertThat(fresh.body()).doesNotContain("id=\"turn-" + first.id() + "\"").contains(FORM)
                .contains("Прежние разговоры")
                .contains("href=\"" + first.page() + "\"")
                .contains("ответ о дробях");

        assertThat(browser.get(first.page()).body()).contains("id=\"turn-" + first.id() + "\"");
    }

    /** Сценарий «Учитель без роли Администратора»: четыре поля и вставка — только у Администратора. */
    @Test
    void onlyAdministratorHasTheFileFields() {
        String teachers = loggedIn(port, accounts.settled(Role.TEACHER)).get(Addresses.ASSISTANT).body();
        String administrators = loggedIn(port, accounts.settled(Role.ADMINISTRATOR)).get(Addresses.ASSISTANT).body();

        assertThat(teachers).doesNotContain("type=\"file\"").doesNotContain("data-paste-file");
        for (int number = 1; number <= 4; number++) {
            assertThat(administrators).contains("Файл " + number).contains("name=\"file" + number + "\"");
        }
        assertThat(administrators).doesNotContain("name=\"file5\"")
                .contains("data-paste-file").contains("data-paste-status")
                .contains("до четырёх файлов");
    }

    /**
     * Вставка из буфера (Р5) — точечный раздел {@code locus.js}: текст в буфере
     * не трогается, картинка ложится в первое пустое поле «Вставка N.ext»,
     * нет пустого или браузер не даёт присвоить — сообщение в строке формы.
     */
    @Test
    void pasteSectionFillsTheFirstEmptyField() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/js/locus.js"), StandardCharsets.UTF_8);
        String paste = script.substring(script.indexOf("input[type=file][data-paste-file]"));
        String styles = Files.readString(Path.of("src/main/resources/static/css/locus.css"), StandardCharsets.UTF_8);

        assertThat(paste).contains("[data-paste-status]", "addEventListener('paste'", "'text/plain'",
                "'image/'", "input.files.length === 0", "'Вставка ' + number", "'jpeg' ? 'jpg'",
                "new DataTransfer()", "Свободного поля нет", "выберите файл кнопкой");
        assertThat(styles).contains(".turn-files {", "repeat(2, minmax(0, 1fr))");
    }

    /** Сценарий «Снимок листа». */
    @Test
    void administratorsPhotoBecomesTheirAssemblyDraft() throws IOException {
        TestAccounts.Account administrator = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = loggedIn(port, administrator);

        Browser.Page sent = browser.postMultipart(TURNS, Map.of("prompt", "ответ: что на листе?"),
                List.of(new Browser.FilePart("file1", "лист.jpg", "image/jpeg", jpeg())));
        String page = finished(browser, turn(sent));

        LoggedIn.as(administrator);
        AssemblyDraft draft = drafts.own().getFirst();
        assertThat(draft.originalName()).isEqualTo("лист.jpg");
        assertThat(page).contains("Файл 1: загружен черновик сборки " + draft.id().value() + " «лист.jpg»");
    }

    /** Сценарий «Два файла»: два черновика, строки в порядке полей. */
    @Test
    void twoFilesBecomeTwoDraftsInFieldOrder() throws IOException {
        TestAccounts.Account administrator = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = loggedIn(port, administrator);

        Browser.Page sent = browser.postMultipart(TURNS, Map.of("prompt", "ответ: что на листе?"),
                List.of(new Browser.FilePart("file2", "кадр два.jpg", "image/jpeg", jpeg()),
                        new Browser.FilePart("file1", "кадр один.jpg", "image/jpeg", jpeg())));
        String page = finished(browser, turn(sent));

        LoggedIn.as(administrator);
        assertThat(drafts.own()).hasSize(2);
        long first = draftNamed("кадр один.jpg");
        long second = draftNamed("кадр два.jpg");
        int one = page.indexOf("Файл 1: загружен черновик сборки " + first + " «кадр один.jpg»");
        int two = page.indexOf("Файл 2: загружен черновик сборки " + second + " «кадр два.jpg»");
        assertThat(one).isPositive().isLessThan(two);
    }

    /** Сценарий «Пустое поле между заполненными»: номера не сдвигаются. */
    @Test
    void emptyFieldKeepsTheNumbersOfOthers() throws IOException {
        Browser browser = loggedIn(port, accounts.settled(Role.ADMINISTRATOR));

        Browser.Page sent = browser.postMultipart(TURNS, Map.of("prompt", "ответ: что на листе?"),
                List.of(new Browser.FilePart("file1", "условия.jpg", "image/jpeg", jpeg()),
                        new Browser.FilePart("file2", "", "application/octet-stream", new byte[0]),
                        new Browser.FilePart("file3", "решения.jpg", "image/jpeg", jpeg())));
        String page = finished(browser, turn(sent));

        assertThat(page).contains("Файл 1: загружен черновик сборки").contains("«условия.jpg»")
                .contains("Файл 3: загружен черновик сборки").contains("«решения.jpg»")
                .doesNotContain("Файл 2:");
    }

    /** Сценарий «Неподходящий файл»: отказ называет поле, черновиков нет, текст на месте. */
    @Test
    void unsuitableFileRefusesTheWholeRequest() throws IOException {
        TestAccounts.Account administrator = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = loggedIn(port, administrator);

        Browser.Page refused = browser.postMultipart(TURNS, Map.of("prompt", "разбери лист двумя кадрами"),
                List.of(new Browser.FilePart("file1", "лист.jpg", "image/jpeg", jpeg()),
                        new Browser.FilePart("file2", "заметки.txt", "text/plain",
                                "не картинка".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Файл 2: ").contains("«заметки.txt»")
                .contains("разбери лист двумя кадрами")
                .doesNotContain("id=\"turn-");
        LoggedIn.as(administrator);
        assertThat(drafts.own()).isEmpty();
    }

    /** Сценарий «Пустой запрос». */
    @Test
    void emptyPromptIsRefusedOnTheSamePage() {
        Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

        Browser.Page refused = browser.postForm(TURNS, Map.of("prompt", "  "));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Запрос Помощнику пуст").contains(FORM).doesNotContain(REFRESH);
        assertThat(browser.get(Addresses.ASSISTANT).body()).doesNotContain("id=\"turn-");
    }

    /** Сценарий «Без команды». */
    @Nested
    @TestPropertySource(properties = "locus.agent.command=target/no-such-claude")
    class WithoutTheCommand {

        @LocalServerPort
        private int port;

        @Autowired
        private TestAccounts accounts;

        @Test
        void pageNamesTheCauseHasNoFormAndOtherPagesWork() {
            Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

            Browser.Page page = browser.get(Addresses.ASSISTANT);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.body()).contains("Помощник недоступен: команда Claude Code не найдена")
                    .contains("target/no-such-claude")
                    .doesNotContain(FORM)
                    .doesNotContain("Новый разговор");

            assertThat(browser.get(Addresses.HOME).status()).isEqualTo(200);
            assertThat(browser.get(Addresses.PROBLEMS).status()).isEqualTo(200);
            assertThat(browser.get(Addresses.SCHEDULE).status()).isEqualTo(200);
        }
    }

    /**
     * Сценарий «Сверх предела загрузки» — на пределе контейнера в килобайты,
     * чтобы не гонять по сети сотню мегабайт. Запрос отклоняет контейнер,
     * а страница ошибки переадресует в Разговор из адреса действия; отказ
     * показывается один раз.
     */
    @Nested
    @TestPropertySource(properties = {
            "spring.servlet.multipart.max-file-size=2KB",
            "spring.servlet.multipart.max-request-size=4KB"})
    class OverTheUploadLimit {

        @LocalServerPort
        private int port;

        @Autowired
        private TestAccounts accounts;

        @Test
        void refusalStaysInTheSameConversationAndSaysTheTextIsLost() {
            Browser browser = loggedIn(port, accounts.settled(Role.ADMINISTRATOR));
            Turn earlier = send(browser, "ответ до предела");
            finished(browser, earlier);

            Browser.Page refused = browser.postMultipart(TURNS + "?conversation=" + earlier.conversation(),
                    Map.of("prompt", "разбери тяжёлый лист"),
                    List.of(new Browser.FilePart("file1", "тяжёлый.pdf", "application/pdf", new byte[8 * 1024])));

            assertThat(refused.location()).endsWith(earlier.page());
            String page = browser.get(earlier.page()).body();
            assertThat(page).contains("Файлы слишком велики: до 2 КБ на файл и до 4 КБ за один запрос")
                    .contains("Набранный текст не сохранился")
                    .contains("id=\"turn-" + earlier.id() + "\"")
                    .contains("action=\"" + TURNS + "?conversation=" + earlier.conversation() + "\"")
                    .doesNotContain("разбери тяжёлый лист");
            assertThat(browser.get(earlier.page()).body()).doesNotContain("Файлы слишком велики");
        }
    }

    /**
     * Начатая со страницы Реплика.
     *
     * @param page адрес её Разговора, без якоря
     */
    private record Turn(String page, long conversation, long id) {
    }

    private static Turn send(Browser browser, String prompt) {
        return turn(browser.postForm(TURNS, Map.of("prompt", prompt)));
    }

    private static Turn send(Browser browser, String prompt, long conversation) {
        return turn(browser.postForm(TURNS + "?conversation=" + conversation, Map.of("prompt", prompt)));
    }

    /** Отправка ведёт на адрес Разговора с якорем Реплики (Р1). */
    private static Turn turn(Browser.Page sent) {
        assertThat(sent.status()).as("отправка — переадресация, а не отказ").isEqualTo(302);
        Matcher address = TURN_ADDRESS.matcher(sent.location());
        assertThat(address.find()).as(sent.location()).isTrue();
        return new Turn(address.group(1), Long.parseLong(address.group(2)), Long.parseLong(address.group(3)));
    }

    /** Страница Разговора, когда Реплика кончилась: перечитывается, пока стоит самообновление. */
    private static String finished(Browser browser, Turn turn) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            String body = browser.get(turn.page()).body();
            if (!body.contains(REFRESH)) {
                return body;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Реплика " + turn.id() + " не завершилась за " + PATIENCE);
    }

    private static Browser loggedIn(int port, TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }

    /** Номер своего черновика с этим именем; зовётся под личностью его владельца. */
    private long draftNamed(String name) {
        return drafts.own().stream().filter(draft -> draft.originalName().equals(name))
                .findFirst().orElseThrow().id().value();
    }

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }
}
