package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Требование «Учитель видит и ведёт только свои Работы», последний абзац:
 * операции над Работами доступны только роли Учителя — и экраны,
 * и приём, и правка.
 *
 * Как у Заданий ({@code AssignmentAccessTest}): Администратор без роли
 * Учителя не получает даже экрана — у него нет Работ, и экран для него
 * не пуст, а недоступен. Отказ проверяется обращением по прямому адресу.
 * Обстановка — через репозитории от имени Учителя.
 */
class StudentWorkAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private StudentRepository students;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    private TestAccounts.Account owner;
    private StudentId student;
    private ProblemId problem;
    private AssignmentId assignment;
    private StudentWorkId work;
    private StudentWorkFileId file;

    @BeforeEach
    void setTheScene() {
        owner = accounts.settled(Role.TEACHER);
        student = students.create(owner.id(), "Иванов Пётр");
        problem = library.problem(library.topic());
        ProblemId second = library.problem(library.topic());
        LocalDate today = LocalDate.now();
        assignment = assignments.create(owner.id(), student, null, today, today.plusDays(7), TheoryScope.NONE,
                List.of(problem, second));
        work = works.create(owner.id(), assignment, problem, today, null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg"), new FileKey(UUID.randomUUID() + ".jpg")));
        file = works.findById(owner.id(), work).orElseThrow().files().get(0).id();
    }

    /** Сценарий «Администратор без роли Учителя»: ни экрана приёма, ни списка. */
    @Test
    void administratorWithoutTeacherRoleIsRefusedEveryScreen() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        assertThat(administrator.get("/works?assignment=" + assignment.value()).status()).as("экран приёма").isEqualTo(403);
        assertThat(administrator.get("/works?student=" + student.value()).status()).as("список Работ").isEqualTo(403);
    }

    /** Сценарий «Администратор без роли Учителя»: ни одной операции. */
    @Test
    void administratorWithoutTeacherRoleIsRefusedEveryOperation() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);
        String prefix = "/works/" + work.value();

        assertThat(administrator.postMultipart("/works?assignment=" + assignment.value(),
                Map.of("problem", String.valueOf(problem.value())), Map.of("files", TestLibrary.pdf())).status())
                .as("приём").isEqualTo(403);
        assertThat(administrator.postMultipart(prefix + "/files", Map.of(), Map.of("files", TestLibrary.pdf())).status())
                .as("добавление файлов").isEqualTo(403);
        assertThat(administrator.postForm(prefix + "/files/" + file.value() + "/deletion", Map.of()).status())
                .as("удаление файла").isEqualTo(403);
        assertThat(administrator.postForm(prefix + "/verdict", Map.of("verdict", "CORRECT")).status())
                .as("вердикт").isEqualTo(403);
        assertThat(administrator.postForm(prefix + "/deletion", Map.of()).status())
                .as("удаление Работы").isEqualTo(403);

        StudentWork left = works.findById(owner.id(), work).orElseThrow();
        assertThat(left.files()).as("файлы не тронуты").hasSize(2);
        assertThat(left.isChecked()).as("вердикт не поставлен").isFalse();
        assertThat(works.findByAssignment(owner.id(), assignment)).as("ничего не принято и не удалено").hasSize(1);
    }

    /** Учитель, он же Администратор, к своим Работам допущен: роли складываются. */
    @Test
    void teacherIsAdmittedToWorks() {
        TestAccounts.Account both = accounts.settled(Role.TEACHER, Role.ADMINISTRATOR);
        StudentId own = students.create(both.id(), "Петрова Анна");
        LocalDate today = LocalDate.now();
        AssignmentId ownAssignment = assignments.create(both.id(), own, null, today, today.plusDays(7),
                TheoryScope.NONE, List.of(problem));
        StudentWorkId ownWork = works.create(both.id(), ownAssignment, problem, today, null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
        Browser teacher = new Browser(port);
        teacher.logIn(both.login(), both.password());

        assertThat(teacher.get("/works?assignment=" + ownAssignment.value()).status()).isEqualTo(200);
        assertThat(teacher.get("/works?student=" + own.value()).status()).isEqualTo(200);
        assertThat(teacher.postForm("/works/" + ownWork.value() + "/verdict",
                Map.of("verdict", "CORRECT", "note", "чисто")).status())
                .as("операция выполнена, ответ — переадресация")
                .isIn(302, 303);
        assertThat(works.findById(both.id(), ownWork).orElseThrow().verdict()).isEqualTo(Verdict.CORRECT);
    }

    /** Без входа каждый адрес приводит к форме входа. */
    @Test
    void withoutLoginEveryAddressLeadsToTheLoginForm() {
        Browser visitor = new Browser(port);

        for (String address : List.of("/works?assignment=" + assignment.value(), "/works?student=" + student.value(),
                "/works")) {
            Browser.Page page = visitor.get(address);
            assertThat(page.redirectsTo("/login")).as("%s приводит к форме входа", address).isTrue();
            assertThat(page.body()).doesNotContain("Иванов Пётр");
        }
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
