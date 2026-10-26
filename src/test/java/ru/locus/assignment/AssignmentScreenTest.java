package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Экраны Заданий: от отмеченного в поиске к форме, выдача Ученику и Группе,
 * страницы Задания и Раздачи, отказы формы, перенос срока, удаление
 * и сводка с отбором.
 *
 * Сравнивается видимый человеку текст, а не разметка целиком
 * (antipatterns.md, «Сравнение разметки страницы целиком»); исключение —
 * скрытые поля с номерами Задач и строки таблицы по {@code id}: человеку
 * они видны как содержимое, а в тексте страницы их нет.
 *
 * Обстановка — через репозитории с владельцем из учётной записи, как
 * в {@code GroupScreenTest}: экран проверяется с той стороны, с которой
 * его видит Учитель, а заводить Учеников через тот же экран — проверять
 * два экрана одним тестом. Часы — {@link TestClock}: «не сдано» меняется
 * от времени, а не от действий (спека, «Сдвиг часов»).
 */
class AssignmentScreenTest extends IntegrationTest {

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
    private AssignmentRepository assignments;

    @Autowired
    private TestClock clock;

    private TestAccounts.Account account;
    private Browser teacher;
    private StudentId student;
    private String studentName;
    private ProblemId first;
    private ProblemId second;

    @BeforeEach
    void setTheScene() {
        account = accounts.settled(Role.TEACHER);
        teacher = loggedIn(account);
        studentName = TestLibrary.unique("Иванов Пётр");
        student = students.create(account.id(), studentName);
        first = library.problem(library.topic());
        second = library.problem(library.topic());
    }

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    /** Спека, «От найденного к выдаче»: форма показывает отмеченные Задачи, Учеников и Группы. */
    @Test
    void formShowsTheMarkedProblemsAndOffersTheTeachersStudentsAndGroups() {
        String groupName = TestLibrary.unique("9Б");
        groups.create(account.id(), groupName);

        Browser.Page page = teacher.get("/assignments/new?problem=" + first.value() + "&problem=" + second.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("№ " + first.value()).contains("№ " + second.value())
                .contains(hidden(first)).contains(hidden(second))
                .contains("Темы: ").contains("Методы: ")
                .contains(studentName).contains(groupName)
                .doesNotContain("th:text");
    }

    /** Спека, «Выдача Ученику»: после формы — страница Задания с составом, сроком и Учеником. */
    @Test
    void issuingToAStudentLeadsToTheAssignmentPage() {
        LocalDate due = today().plusDays(7);

        Browser.Page done = teacher.postForm("/assignments", issue(due, "student", student.value()));

        assertThat(done.status()).isIn(302, 303);
        assertThat(done.location()).matches(".*/assignments/\\d+$");
        Browser.Page page = teacher.get(relative(done.location()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Задание для " + studentName)
                .contains("№ " + first.value()).contains("№ " + second.value())
                .contains("Выдано: " + SHOWN.format(today()))
                .contains("Срок: " + SHOWN.format(due))
                .contains("Теория: только Темы задач")
                .doesNotContain("не сдано")
                .doesNotContain("к Раздаче");
        assertThat(page.body().indexOf("№ " + first.value()))
                .as("состав в порядке выбора")
                .isLessThan(page.body().indexOf("№ " + second.value()));
    }

    /** Спека, «Выдача Группе»: страница Раздачи с именем Группы и Учениками. */
    @Test
    void issuingToAGroupLeadsToTheBatchPageWithEveryMember() {
        String groupName = TestLibrary.unique("9Б");
        String otherName = TestLibrary.unique("Петрова Анна");
        StudentId other = students.create(account.id(), otherName);
        GroupId group = groups.create(account.id(), groupName);
        groups.setMembers(account.id(), group, List.of(student, other));

        Browser.Page done = teacher.postForm("/assignments", issue(today().plusDays(7), "group", group.value()));

        assertThat(done.status()).isIn(302, 303);
        assertThat(done.location()).matches(".*/assignments/batches/\\d+$");
        Browser.Page page = teacher.get(relative(done.location()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Раздача Группе «" + groupName + "»")
                .contains("Выдано: " + SHOWN.format(today()))
                .contains(studentName).contains(otherName);
        assertThat(assignments.find(account.id(), null, null, null, null))
                .as("по Заданию на каждого члена")
                .hasSize(2);
    }

    /** Спека, «Группа после выдачи переименована и удалена»: Раздача помнит имя. */
    @Test
    void renamingAndDeletingTheGroupAfterwardsLeaveTheBatchAsItWas() {
        String groupName = TestLibrary.unique("9Б");
        GroupId group = groups.create(account.id(), groupName);
        groups.setMembers(account.id(), group, List.of(student));
        String batchPage = relative(teacher.postForm("/assignments",
                issue(today().plusDays(7), "group", group.value())).location());

        teacher.postForm("/groups/" + group.value() + "/name", Map.of("name", TestLibrary.unique("10Б")));
        teacher.postForm("/groups/" + group.value() + "/deletion", Map.of());

        Browser.Page page = teacher.get(batchPage);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Раздача Группе «" + groupName + "»").contains(studentName);
        assertThat(teacher.get("/groups/" + group.value()).status()).as("Группы больше нет").isEqualTo(404);
    }

    /** Спека, «Задание без срока», «Задание без Задач», «Адресат не выбран или выбраны оба». */
    @Test
    void refusalsComeBackToTheFormWithAMessage() {
        Browser.Page noDueDate = teacher.postForm("/assignments", List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("student", String.valueOf(student.value()))));
        assertThat(noDueDate.status()).isEqualTo(200);
        assertThat(noDueDate.body()).contains("Срок Задания обязателен").contains(hidden(first));

        Browser.Page noProblems = teacher.postForm("/assignments", List.of(
                Map.entry("dueDate", today().toString()),
                Map.entry("student", String.valueOf(student.value()))));
        assertThat(noProblems.status()).isEqualTo(200);
        assertThat(noProblems.body()).contains("хотя бы одна Задача");

        GroupId group = groups.create(account.id(), TestLibrary.unique("9Б"));
        Browser.Page both = teacher.postForm("/assignments", List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("dueDate", today().toString()),
                Map.entry("student", String.valueOf(student.value())),
                Map.entry("group", String.valueOf(group.value()))));
        assertThat(both.status()).isEqualTo(200);
        assertThat(both.body()).contains("адресат один: Ученик либо Группа");

        Browser.Page nobody = teacher.postForm("/assignments", List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("dueDate", today().toString())));
        assertThat(nobody.status()).isEqualTo(200);
        assertThat(nobody.body()).contains("адресат один: Ученик либо Группа");

        assertThat(assignments.find(account.id(), null, null, null, null)).as("ничего не выдано").isEmpty();
    }

    /** Спека, «Перенос срока». */
    @Test
    void dueDateIsMovedThroughTheForm() {
        String page = relative(teacher.postForm("/assignments",
                issue(today().plusDays(7), "student", student.value())).location());
        LocalDate moved = today().plusDays(14);

        Browser.Page done = teacher.postForm(page + "/due-date", Map.of("dueDate", moved.toString()));

        assertThat(done.redirectsTo(page)).isTrue();
        assertThat(teacher.get(page).body()).contains("Срок: " + SHOWN.format(moved));

        Browser.Page refused = teacher.postForm(page + "/due-date", Map.of());
        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Срок Задания обязателен").contains("Срок: " + SHOWN.format(moved));
    }

    /** Спека, «Удаление Задания без Работ» и «Удаление Раздачи». */
    @Test
    void assignmentAndBatchAreDeletedThroughTheForms() {
        String assignmentPage = relative(teacher.postForm("/assignments",
                issue(today().plusDays(7), "student", student.value())).location());
        GroupId group = groups.create(account.id(), TestLibrary.unique("9Б"));
        groups.setMembers(account.id(), group, List.of(student));
        String batchPage = relative(teacher.postForm("/assignments",
                issue(today().plusDays(7), "group", group.value())).location());

        Browser.Page assignmentGone = teacher.postForm(assignmentPage + "/deletion", Map.of());
        assertThat(assignmentGone.redirectsTo("/assignments")).isTrue();
        assertThat(teacher.get(assignmentPage).status()).isEqualTo(404);
        assertThat(assignments.find(account.id(), null, null, null, null)).as("Задание Раздачи осталось").hasSize(1);

        Browser.Page batchGone = teacher.postForm(batchPage + "/deletion", Map.of());
        assertThat(batchGone.redirectsTo("/assignments")).isTrue();
        assertThat(teacher.get(batchPage).status()).isEqualTo(404);
        assertThat(assignments.find(account.id(), null, null, null, null)).as("ушла со своим Заданием").isEmpty();
    }

    /** Спека, «По Ученику» и «Только несданные за период» — на сводке и на часах. */
    @Test
    void summaryFiltersByStudentAndShowsNotSubmittedByTheClock() {
        StudentId other = students.create(account.id(), TestLibrary.unique("Петрова Анна"));
        AssignmentId soon = issued(student, today().plusDays(2));
        AssignmentId later = issued(other, today().plusDays(10));

        String all = teacher.get("/assignments").body();
        assertThat(all).contains(row(soon)).contains(row(later)).doesNotContain("не сдано");

        String ofStudent = teacher.get("/assignments?student=" + student.value()).body();
        assertThat(ofStudent).contains(row(soon)).doesNotContain(row(later));

        clock.shift(Duration.ofDays(3));

        String overdue = teacher.get("/assignments?notSubmitted=true").body();
        assertThat(overdue).contains(row(soon)).contains("не сдано").doesNotContain(row(later));
        assertThat(teacher.get("/assignments?notSubmitted=true&dueFrom=" + today().plusDays(5)
                + "&dueTo=" + today().plusDays(20)).body())
                .as("несданных в этом периоде нет")
                .doesNotContain(row(soon)).doesNotContain(row(later));

        clock.reset();
        assertThat(teacher.get("/assignments?notSubmitted=true").body())
                .as("часы вернулись — сдавать ещё не поздно")
                .doesNotContain(row(soon));
    }

    /**
     * Возврат переживает отбор (ADR-0060): сводка, открытая с карточки
     * Ученика, показывает «назад» на неё, а форма отбора несёт возврат
     * скрытым полем. Период срока — {@code dueFrom} и {@code dueTo}:
     * {@code from} — возврат, и дата под этим именем ломала переход
     * с карточки (design.md, Р9).
     */
    @Test
    void comingFromTheStudentShowsBackAndTheFilterFormCarriesIt() {
        AssignmentId soon = issued(student, today().plusDays(2));
        String card = "/students/" + student.value();

        Browser.Page page = teacher.get("/assignments?student=" + student.value() + "&from=" + card);

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains(row(soon))
                .contains("<a href=\"" + card + "\">← Назад</a>")
                .contains("<input type=\"hidden\" name=\"from\" value=\"" + card + "\">")
                .contains("href=\"/assignments?from=%2Fstudents%2F" + student.value() + "\"")
                .contains("name=\"dueFrom\"").contains("name=\"dueTo\"");
    }

    /** Сводка — раздел шапки: без возврата ни «назад», ни скрытого поля. */
    @Test
    void summaryWithoutAReturnHasNoBackAndNoHiddenReturn() {
        String body = teacher.get("/assignments").body();

        assertThat(body).doesNotContain("← Назад").doesNotContain("name=\"from\"")
                .contains("href=\"/assignments\"");
    }

    /** Спека, «Задача выдана Группе»: строка с именем Группы и датой выдачи. */
    @Test
    void formNamesThePriorIssueToAGroup() {
        String groupName = TestLibrary.unique("9Б");
        GroupId group = groups.create(account.id(), groupName);
        groups.setMembers(account.id(), group, List.of(student));
        teacher.postForm("/assignments", issue(today().plusDays(7), "group", group.value()));

        String body = teacher.get("/assignments/new?problem=" + first.value()).body();

        assertThat(body).contains("Уже выдана: «" + groupName + "» — " + SHOWN.format(today()) + "<");
    }

    /** Спека, «Задача выдана Ученику лично»: строка с именем Ученика и датой выдачи. */
    @Test
    void formNamesThePriorIssueToAStudent() {
        teacher.postForm("/assignments", issue(today().plusDays(7), "student", student.value()));

        String body = teacher.get("/assignments/new?problem=" + first.value()).body();

        assertThat(body).contains("Уже выдана: " + studentName + " — " + SHOWN.format(today()) + "<");
    }

    /** Спека, «Задача выдана другим Учителем» и «Задача не выдавалась»: строки нет. */
    @Test
    void issuesOfAnotherTeacherAndNeverIssuedProblemsGiveNoLine() {
        TestAccounts.Account otherTeacher = accounts.settled(Role.TEACHER);
        StudentId theirs = students.create(otherTeacher.id(), TestLibrary.unique("Сидорова Анна"));
        assignments.create(otherTeacher.id(), theirs, null, today(), today().plusDays(7), TheoryScope.NONE,
                List.of(first));

        String body = teacher.get("/assignments/new?problem=" + first.value() + "&problem=" + second.value()).body();

        assertThat(body).contains(hidden(first)).contains(hidden(second))
                .doesNotContain("Уже выдана");
    }

    /** Спека, «Прежних выдач больше трёх»: три новейшие и число остальных. */
    @Test
    void moreThanThreePriorIssuesShowTheNewestThreeAndCountTheRest() {
        for (int daysAgo = 1; daysAgo <= 5; daysAgo++) {
            assignments.create(account.id(), student, null, today().minusDays(daysAgo), today().plusDays(7),
                    TheoryScope.NONE, List.of(first));
        }

        String body = teacher.get("/assignments/new?problem=" + first.value()).body();

        assertThat(body).contains("Уже выдана: "
                + studentName + " — " + SHOWN.format(today().minusDays(1)) + "; "
                + studentName + " — " + SHOWN.format(today().minusDays(2)) + "; "
                + studentName + " — " + SHOWN.format(today().minusDays(3)) + " и ещё 2<");
        assertThat(body).doesNotContain(SHOWN.format(today().minusDays(4)));
    }

    /** Спека, «Повторная выдача тому же адресату проходит»: вторая Раздача, прежняя остаётся. */
    @Test
    void repeatedIssueToTheSameGroupPassesAndMakesASecondBatch() {
        String groupName = TestLibrary.unique("9Б");
        GroupId group = groups.create(account.id(), groupName);
        groups.setMembers(account.id(), group, List.of(student));

        Browser.Page once = teacher.postForm("/assignments", issue(today().plusDays(7), "group", group.value()));
        Browser.Page twice = teacher.postForm("/assignments", issue(today().plusDays(7), "group", group.value()));

        assertThat(once.location()).matches(".*/assignments/batches/\\d+$");
        assertThat(twice.location()).matches(".*/assignments/batches/\\d+$").isNotEqualTo(once.location());
        assertThat(teacher.get(relative(once.location())).status()).isEqualTo(200);
        String label = "«" + groupName + "» — " + SHOWN.format(today());
        assertThat(teacher.get("/assignments/new?problem=" + first.value()).body())
                .contains("Уже выдана: " + label + "; " + label + "<");
    }

    private AssignmentId issued(StudentId to, LocalDate due) {
        String page = relative(teacher.postForm("/assignments", issue(due, "student", to.value())).location());
        return new AssignmentId(Long.parseLong(page.substring(page.lastIndexOf('/') + 1)));
    }

    private List<Map.Entry<String, String>> issue(LocalDate due, String addressee, long id) {
        return List.of(
                Map.entry("problem", String.valueOf(first.value())),
                Map.entry("problem", String.valueOf(second.value())),
                Map.entry("dueDate", due.toString()),
                Map.entry("theoryScope", TheoryScope.TOPICS.name()),
                Map.entry(addressee, String.valueOf(id)));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String hidden(ProblemId id) {
        return "name=\"problem\" value=\"" + id.value() + "\"";
    }

    private static String row(AssignmentId id) {
        return "id=\"assignment-" + id.value() + "\"";
    }

    private static String relative(String location) {
        return location.substring(location.indexOf("/assignments"));
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
