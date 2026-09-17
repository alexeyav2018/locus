package ru.locus.mastery;

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
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.work.StudentWorkRepository;

/**
 * Задача 6.3: простановка отметок Владения доступна только роли Учителя.
 *
 * Как у Работ ({@code StudentWorkAccessTest}): Администратор без роли
 * Учителя получает 403 по прямому адресу, и ни одной отметки
 * не появляется; невошедший — форму входа; Учитель — переадресацию
 * на экран приёма. Обстановка — через репозитории от имени Учителя.
 */
class MasteryAccessTest extends IntegrationTest {

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

    @Autowired
    private MasteryRepository marks;

    private TestAccounts.Account owner;
    private StudentId student;
    private TaxonomyNodeId topic;
    private SolutionMethodId method;
    private ProblemId problem;
    private AssignmentId assignment;

    @BeforeEach
    void setTheScene() {
        owner = accounts.settled(Role.TEACHER);
        student = students.create(owner.id(), "Иванов Пётр");
        topic = library.topic();
        method = library.method();
        problem = library.problem(topic, method);
        LocalDate today = LocalDate.now();
        assignment = assignments.create(owner.id(), student, null, today, today.plusDays(7), TheoryScope.NONE,
                List.of(problem));
        works.create(owner.id(), assignment, problem, today, null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }

    /** Сценарий «Администратор без роли Учителя»: простановка отклонена, отметок нет. */
    @Test
    void administratorWithoutTeacherRoleIsRefused() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        assertThat(administrator.postForm("/mastery", form("MASTERED")).status()).isEqualTo(403);
        assertThat(marks.countByStudent(owner.id(), student)).as("ни одной отметки").isZero();
    }

    /** Учитель к простановке допущен: ответ — переадресация на экран приёма. */
    @Test
    void teacherIsAdmitted() {
        Browser teacher = new Browser(port);
        teacher.logIn(owner.login(), owner.password());

        Browser.Page posted = teacher.postForm("/mastery", form("MASTERED"));

        assertThat(posted.redirectsTo("/works?assignment=" + assignment.value())).isTrue();
        assertThat(marks.countByStudent(owner.id(), student)).isEqualTo(1);
    }

    /** Без входа — форма входа, и ничего не записано. */
    @Test
    void withoutLoginThePostLeadsToTheLoginForm() {
        Browser visitor = new Browser(port);

        Browser.Page page = visitor.postForm("/mastery", form("MASTERED"));

        assertThat(page.redirectsTo("/login")).isTrue();
        assertThat(marks.countByStudent(owner.id(), student)).isZero();
    }

    private List<Map.Entry<String, String>> form(String status) {
        return List.of(
                Map.entry("assignment", String.valueOf(assignment.value())),
                Map.entry("problem", String.valueOf(problem.value())),
                Map.entry("topic", String.valueOf(topic.value())),
                Map.entry("method", String.valueOf(method.value())),
                Map.entry("status", status));
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
