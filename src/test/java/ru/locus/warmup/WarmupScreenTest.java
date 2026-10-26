package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.mastery.Cell;
import ru.locus.mastery.MasteryRepository;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Экраны Разминок: от отмеченного в поиске к форме, заведение, страница
 * с итогами словами, сохранение итогов и отметок одним действием, отказ
 * с введённым, удаление, пустой список, чужая Разминка и посетитель без входа.
 *
 * Сравнивается видимый человеку текст, а не разметка целиком
 * (antipatterns.md, «Сравнение разметки страницы целиком»); исключение —
 * скрытые поля и выбранные пункты списков: человеку они видны как
 * содержимое формы, а в тексте страницы их нет.
 *
 * Обстановка — через репозитории с владельцем из учётной записи, как
 * в {@code AssignmentScreenTest}.
 */
class WarmupScreenTest extends IntegrationTest {

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private WarmupRepository warmups;

    @Autowired
    private MasteryRepository mastery;

    @Autowired
    private TestClock clock;

    private TestAccounts.Account account;
    private Browser teacher;
    private StudentId student;
    private String studentName;
    private TaxonomyNodeId topic;
    private SolutionMethodId method;
    private ProblemId first;
    private ProblemId second;

    @BeforeEach
    void setTheScene() {
        account = accounts.settled(Role.TEACHER);
        teacher = loggedIn(account);
        studentName = TestLibrary.unique("Иванов Пётр");
        student = students.create(account.id(), studentName);
        topic = library.topic();
        method = library.method();
        first = library.problem(topic, method);
        second = library.problem(library.topic());
    }

    /** Спека, «Переход из поиска»: форма с отмеченными Задачами и сегодняшней датой. */
    @Test
    void searchLeadsToTheFormWithTheMarkedProblemsAndToday() {
        Browser.Page search = teacher.get("/problems?node=" + topic.value());
        assertThat(search.body()).contains("Провести Разминку").contains("formaction=\"/warmups/new\"");

        Browser.Page page = teacher.get("/warmups/new?problem=" + first.value() + "&problem=" + second.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Новая Разминка")
                .contains(hidden(first)).contains(hidden(second))
                .contains("value=\"" + today() + "\"")
                .contains(studentName)
                .doesNotContain("th:text");
    }

    /** Спека, «После заведения»: на страницу Разминки с Задачами и участником. */
    @Test
    void holdingLeadsToTheWarmupPage() {
        Browser.Page done = teacher.postForm("/warmups", hold("student", student.value()));

        assertThat(done.status()).isIn(302, 303);
        assertThat(done.location()).matches(".*/warmups/\\d+$");
        Browser.Page page = teacher.get(relative(done.location()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Разминка " + SHOWN.format(today()))
                .contains("Ученик: " + studentName)
                .contains("№ " + first.value()).contains("№ " + second.value());
    }

    /** Заведённая Разминка Группе названа именем Группы. */
    @Test
    void warmupOfAGroupIsNamedAfterTheGroup() {
        String groupName = TestLibrary.unique("9Б");
        GroupId group = groups.create(account.id(), groupName);
        groups.setMembers(account.id(), group, List.of(student));

        String page = relative(teacher.postForm("/warmups", hold("group", group.value())).location());

        assertThat(teacher.get(page).body()).contains("Группа «" + groupName + "»").contains(studentName);
    }

    /** Спека, «Разминка без Задач», «Адресата нет или их два»: форма снова с сообщением. */
    @Test
    void refusalsComeBackToTheFormWithAMessage() {
        Browser.Page noProblems = teacher.postForm("/warmups", List.of(
                Map.entry("heldOn", today().toString()),
                Map.entry("student", String.valueOf(student.value()))));
        assertThat(noProblems.status()).isEqualTo(200);
        assertThat(noProblems.body()).contains("хотя бы одна Задача");

        Browser.Page nobody = teacher.postForm("/warmups", List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("heldOn", today().toString())));
        assertThat(nobody.status()).isEqualTo(200);
        assertThat(nobody.body()).contains("адресат один: Ученик либо Группа").contains(hidden(first));
    }

    /** Спека, «Итоги участника», «Пустая клетка»: итоги словами на виду, пустая — «—». */
    @Test
    void outcomesAreShownInWordsAndAnEmptyCellIsADash() {
        WarmupId warmup = held();
        warmups.putOutcome(account.id(), warmup, student, first, WarmupOutcome.SUCCEEDED);

        Browser.Page page = teacher.get("/warmups/" + warmup.value());

        assertThat(page.body())
                .contains("№ " + first.value() + ": справился")
                .contains("№ " + second.value() + ": —");
    }

    /** Итоги и отметки участника сохраняются одним действием. */
    @Test
    void outcomesAndMarksAreSavedTogether() {
        WarmupId warmup = held();

        Browser.Page done = teacher.postForm("/warmups/" + warmup.value() + "/students/" + student.value(),
                outcomes(WarmupOutcome.FAILED, WarmupOutcome.SUCCEEDED, topic, method, MasteryStatus.UNCERTAIN));

        assertThat(done.status()).isIn(302, 303);
        assertThat(done.location()).endsWith("/warmups/" + warmup.value() + "#student-" + student.value());
        assertThat(teacher.get("/warmups/" + warmup.value()).body())
                .contains("№ " + first.value() + ": не справился")
                .contains("№ " + second.value() + ": справился")
                .contains("сейчас: владеет неуверенно");
        assertThat(mastery.findByStudent(account.id(), student))
                .containsEntry(new Cell(topic, method), MasteryStatus.UNCERTAIN);
    }

    /** Отказ по паре: сообщение, раскрыт блок участника, введённое выбрано, ничего не записано. */
    @Test
    void refusedPairComesBackWithTheEnteredAndWritesNothing() {
        WarmupId warmup = held();
        SolutionMethodId foreign = library.method();

        Browser.Page refused = teacher.postForm("/warmups/" + warmup.value() + "/students/" + student.value(),
                outcomes(WarmupOutcome.SUCCEEDED, null, topic, foreign, MasteryStatus.MASTERED));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body())
                .contains("role=\"alert\"")
                .contains("open=\"open\"")
                .contains("value=\"SUCCEEDED\" selected=\"selected\"");
        assertThat(warmups.findOutcomes(account.id(), warmup)).isEmpty();
        assertThat(mastery.findByStudent(account.id(), student)).isEmpty();
    }

    /** Удаление — на список, Разминки больше нет. */
    @Test
    void deletionLeadsToTheList() {
        WarmupId warmup = held();

        Browser.Page done = teacher.postForm("/warmups/" + warmup.value() + "/deletion", Map.of());

        assertThat(done.redirectsTo("/warmups")).isTrue();
        assertThat(teacher.get("/warmups/" + warmup.value()).status()).isEqualTo(404);
    }

    /** Пустой список говорит, что Разминка начинается с поиска; полный — дата и адресат. */
    @Test
    void listSaysWhereAWarmupStartsAndShowsTheWarmups() {
        assertThat(teacher.get("/warmups").body()).contains("Разминка начинается").contains("поиска по библиотеке");

        held();

        Browser.Page list = teacher.get("/warmups?student=" + student.value());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body())
                .contains("Разминки: " + studentName)
                .contains(SHOWN.format(today()) + " — " + studentName)
                .contains("Задач: 2 · участников: 1");
    }

    /** Спека, «Чужая Разминка»: страница, итоги и удаление — 404; без входа — на вход. */
    @Test
    void anotherTeachersWarmupIsNotFoundAndAVisitorIsSentToLogIn() {
        WarmupId warmup = held();
        Browser other = loggedIn(accounts.settled(Role.TEACHER));

        assertThat(other.get("/warmups/" + warmup.value()).status()).isEqualTo(404);
        assertThat(other.postForm("/warmups/" + warmup.value() + "/students/" + student.value(),
                outcomes(WarmupOutcome.SUCCEEDED, null, topic, method, null)).status()).isEqualTo(404);
        assertThat(other.postForm("/warmups/" + warmup.value() + "/deletion", Map.of()).status()).isEqualTo(404);
        assertThat(warmups.findById(account.id(), warmup)).isPresent();

        assertThat(new Browser(port).get("/warmups").redirectsTo("/login")).isTrue();
    }

    private WarmupId held() {
        return warmups.create(account.id(), today(), null, List.of(student), List.of(first, second));
    }

    private List<Map.Entry<String, String>> hold(String addressee, long id) {
        return List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("problem", String.valueOf(second.value())),
                Map.entry("heldOn", today().toString()),
                Map.entry(addressee, String.valueOf(id)));
    }

    private List<Map.Entry<String, String>> outcomes(WarmupOutcome atFirst, WarmupOutcome atSecond,
                                                     TaxonomyNodeId cellTopic, SolutionMethodId cellMethod,
                                                     MasteryStatus status) {
        return List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("outcome", atFirst == null ? "" : atFirst.name()),
                Map.entry("problem", String.valueOf(second.value())),
                Map.entry("outcome", atSecond == null ? "" : atSecond.name()),
                Map.entry("topic", String.valueOf(cellTopic.value())),
                Map.entry("method", String.valueOf(cellMethod.value())),
                Map.entry("status", status == null ? "" : status.name()));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String hidden(ProblemId id) {
        return "name=\"problem\" value=\"" + id.value() + "\"";
    }

    private static String relative(String location) {
        return location.substring(location.indexOf("/warmups"));
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
