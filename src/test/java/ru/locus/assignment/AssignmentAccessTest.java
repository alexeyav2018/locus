package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Требование «Учитель видит и ведёт только свои Задания», последний абзац:
 * операции над Заданиями доступны только роли Учителя — и чтение,
 * и выдача, и правка.
 *
 * Как у Учеников ({@code StudentAccessTest}): Администратор без роли
 * Учителя не получает даже сводки — у него нет Заданий, и сводка для него
 * не пуста, а недоступна. Отказ проверяется обращением по прямому адресу,
 * а не отсутствием ссылки на главной: доступность определяется правами,
 * а не разметкой. Обстановка — через репозитории от имени Учителя: тесту
 * нужно, чтобы Задание и Раздача существовали, а не проверить права
 * на их выдачу.
 */
class AssignmentAccessTest extends IntegrationTest {

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
    private AssignmentBatchRepository batches;

    private TestAccounts.Account owner;
    private StudentId student;
    private GroupId group;
    private ProblemId problem;
    private AssignmentId assignment;
    private AssignmentBatchId batch;
    private LocalDate due;

    @BeforeEach
    void setTheScene() {
        owner = accounts.settled(Role.TEACHER);
        student = students.create(owner.id(), "Иванов Пётр");
        group = groups.create(owner.id(), "9Б");
        groups.setMembers(owner.id(), group, List.of(student));
        problem = library.problem(library.topic());
        LocalDate today = LocalDate.now();
        due = today.plusDays(7);
        batch = batches.create(owner.id(), "9Б", today);
        assignment = assignments.create(owner.id(), student, batch, today, due, TheoryScope.NONE, List.of(problem));
    }

    /** Сценарий «Администратор без роли Учителя»: ни сводки, ни формы, ни страниц. */
    @Test
    void administratorWithoutTeacherRoleIsRefusedEveryScreen() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        assertThat(administrator.get("/assignments").status()).as("сводка").isEqualTo(403);
        assertThat(administrator.get("/assignments/new?problem=" + problem.value()).status())
                .as("форма выдачи").isEqualTo(403);
        assertThat(administrator.get("/assignments/" + assignment.value()).status()).as("Задание").isEqualTo(403);
        assertThat(administrator.get("/assignments/batches/" + batch.value()).status()).as("Раздача").isEqualTo(403);
    }

    /** Сценарий «Администратор без роли Учителя»: ни одной операции. */
    @Test
    void administratorWithoutTeacherRoleIsRefusedEveryOperation() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        assertThat(administrator.postForm("/assignments", List.of(
                Map.entry("problem", String.valueOf(problem.value())),
                Map.entry("dueDate", due.toString()),
                Map.entry("student", String.valueOf(student.value())))).status())
                .as("выдача Ученику").isEqualTo(403);
        assertThat(administrator.postForm("/assignments", List.of(
                Map.entry("problem", String.valueOf(problem.value())),
                Map.entry("dueDate", due.toString()),
                Map.entry("group", String.valueOf(group.value())))).status())
                .as("выдача Группе").isEqualTo(403);
        assertThat(administrator.postForm("/assignments/" + assignment.value() + "/due-date",
                Map.of("dueDate", due.plusDays(1).toString())).status())
                .as("перенос срока").isEqualTo(403);
        assertThat(administrator.postForm("/assignments/" + assignment.value() + "/deletion", Map.of()).status())
                .as("удаление Задания").isEqualTo(403);
        assertThat(administrator.postForm("/assignments/batches/" + batch.value() + "/deletion", Map.of()).status())
                .as("удаление Раздачи").isEqualTo(403);

        List<Assignment> left = assignments.find(owner.id(), null, null, null, null);
        assertThat(left).as("ничего не выдано и не удалено").hasSize(1);
        assertThat(left.get(0).dueDate()).as("срок не тронут").isEqualTo(due);
        assertThat(batches.findAll(owner.id())).hasSize(1);
    }

    /** Учитель, он же Администратор, к своим Заданиям допущен: роли складываются. */
    @Test
    void teacherIsAdmittedToAssignments() {
        TestAccounts.Account both = accounts.settled(Role.TEACHER, Role.ADMINISTRATOR);
        StudentId own = students.create(both.id(), "Петрова Анна");
        AssignmentId ownAssignment = assignments.create(both.id(), own, null,
                LocalDate.now(), due, TheoryScope.NONE, List.of(problem));
        Browser teacher = new Browser(port);
        teacher.logIn(both.login(), both.password());

        assertThat(teacher.get("/assignments").status()).isEqualTo(200);
        assertThat(teacher.get("/assignments/new?problem=" + problem.value()).status()).isEqualTo(200);
        assertThat(teacher.get("/assignments/" + ownAssignment.value()).status()).isEqualTo(200);
        assertThat(teacher.postForm("/assignments/" + ownAssignment.value() + "/due-date",
                Map.of("dueDate", due.plusDays(1).toString())).status())
                .as("операция выполнена, ответ — переадресация")
                .isIn(302, 303);
        assertThat(assignments.findById(both.id(), ownAssignment).orElseThrow().dueDate()).isEqualTo(due.plusDays(1));
    }

    /** Без входа каждый адрес приводит к форме входа. */
    @Test
    void withoutLoginEveryAddressLeadsToTheLoginForm() {
        Browser visitor = new Browser(port);

        for (String address : List.of("/assignments", "/assignments/new?problem=" + problem.value(),
                "/assignments/" + assignment.value(), "/assignments/batches/" + batch.value())) {
            Browser.Page page = visitor.get(address);
            assertThat(page.redirectsTo("/login")).as("%s приводит к форме входа", address).isTrue();
            assertThat(page.body()).doesNotContain("Иванов Пётр").doesNotContain("9Б");
        }
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
