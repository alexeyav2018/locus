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
 * Граница общего и личного для отметок Владения, обе половины разом
 * и глазами двух Учителей — по образцу {@link ru.locus.work.WorksAreFilteredByOwnerTest}.
 *
 * Первая половина: отметки — личный контур. Простановка по чужому
 * Заданию не выполняется — ответ такой же, как для несуществующего,
 * и отметки владельца остаются какими были: ни новых, ни изменённых.
 * Вторая половина: два Учителя ставят отметки своим Ученикам на одной
 * и той же паре «Тема × Метод» библиотеки, и каждый видит на экране
 * приёма только свою — пара общая, отметки личные, и фильтр по владельцу
 * лёг ровно на отметки. Та же граница проверена и на экране Владения
 * (`mastery-views`): чужой Ученик — 404, а «не владеет» на одной паре
 * у обоих даёт каждому свой, отдельный пробел.
 *
 * Сторона ADR-0036 — что вопросы дерева и словаря считают отметки
 * обоих Учителей без фильтра — проверена в {@link MasteryRestructureTest}
 * и {@link MasteryGuardsTheMethodTest}; здесь она лишь названа
 * в последнем сценарии, чтобы обе половины границы стояли рядом.
 *
 * Проверка идёт через настоящий вход: обе ошибки границы тихие
 * (CLAUDE.md, «Доменный инвариант»), и видны они, только сравнив то,
 * что видят разные люди. Обстановка — через репозитории с {@code UserId}
 * каждого Учителя; простановка формой уже проверена
 * в {@link MasteryScreenTest}.
 */
class MasteryIsFilteredByOwnerTest extends IntegrationTest {

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

    private TestAccounts.Account alice;
    private TestAccounts.Account bob;
    private Browser bobsBrowser;
    private TaxonomyNodeId topic;
    private SolutionMethodId method;
    private ProblemId problem;
    private StudentId alicesStudent;
    private AssignmentId alicesAssignment;

    @BeforeEach
    void setTheScene() {
        alice = accounts.settled(Role.TEACHER);
        bob = accounts.settled(Role.TEACHER);
        bobsBrowser = loggedIn(bob);
        topic = library.topic();
        method = library.method();
        problem = library.problem(topic, method);
        alicesStudent = students.create(alice.id(), TestLibrary.unique("Иванов Пётр"));
        alicesAssignment = issue(alice, alicesStudent);
        receive(alice, alicesAssignment);
    }

    /** Сценарий «Простановка по чужому Заданию»: 404, отметок А не появилось. */
    @Test
    void markingAnotherTeachersAssignmentIsNotFoundAndLeavesNothing() {
        Browser.Page posted = bobsBrowser.postForm("/mastery", form(alicesAssignment, "MASTERED"));

        assertThat(posted.status()).isEqualTo(404);
        assertThat(marks.countByStudent(alice.id(), alicesStudent)).as("у А отметок не появилось").isZero();
        assertThat(marks.countByStudent(bob.id(), alicesStudent)).as("и у Б на чужого Ученика — тоже").isZero();
    }

    /** Та же попытка, когда отметка А уже стоит: она не изменилась. */
    @Test
    void markingAnotherTeachersAssignmentChangesNothing() {
        marks.put(alice.id(), alicesStudent, topic, method, MasteryStatus.NOT_MASTERED);

        Browser.Page posted = bobsBrowser.postForm("/mastery", form(alicesAssignment, "MASTERED"));

        assertThat(posted.status()).isEqualTo(404);
        assertThat(marks.findByStudent(alice.id(), alicesStudent))
                .as("отметка А прежняя")
                .containsExactly(Map.entry(new Cell(topic, method), MasteryStatus.NOT_MASTERED));
        assertThat(marks.countByStudent(bob.id(), alicesStudent)).isZero();
    }

    /** Экран Владения чужого Ученика — 404, как несуществующий. */
    @Test
    void theScreenOfAnotherTeachersStudentIsNotFound() {
        Browser.Page page = bobsBrowser.get("/mastery?student=" + alicesStudent.value());

        assertThat(page.status()).isEqualTo(404);
    }

    /** Обратная половина: одна пара библиотеки, две отметки у двух Учителей, каждый видит свою. */
    @Test
    void twoTeachersMarkTheSamePairAndEachSeesOnlyTheirOwn() {
        StudentId bobsStudent = students.create(bob.id(), TestLibrary.unique("Сидорова Анна"));
        AssignmentId bobsAssignment = issue(bob, bobsStudent);
        receive(bob, bobsAssignment);
        Browser alicesBrowser = loggedIn(alice);

        assertThat(alicesBrowser.postForm("/mastery", form(alicesAssignment, "MASTERED"))
                .redirectsTo("/works?assignment=" + alicesAssignment.value())).isTrue();
        assertThat(bobsBrowser.postForm("/mastery", form(bobsAssignment, "NOT_MASTERED"))
                .redirectsTo("/works?assignment=" + bobsAssignment.value())).isTrue();

        String alicesScreen = alicesBrowser.get("/works?assignment=" + alicesAssignment.value()).body();
        assertThat(alicesScreen).contains("сейчас: владеет ·").doesNotContain("сейчас: не владеет ·");
        String bobsScreen = bobsBrowser.get("/works?assignment=" + bobsAssignment.value()).body();
        assertThat(bobsScreen).contains("сейчас: не владеет ·").doesNotContain("сейчас: владеет ·");

        assertThat(marks.findByStudent(alice.id(), alicesStudent))
                .containsExactly(Map.entry(new Cell(topic, method), MasteryStatus.MASTERED));
        assertThat(marks.findByStudent(bob.id(), bobsStudent))
                .containsExactly(Map.entry(new Cell(topic, method), MasteryStatus.NOT_MASTERED));
        assertThat(marks.findByStudent(bob.id(), alicesStudent)).as("Б не видит отметок Ученика А").isEmpty();
        assertThat(marks.countByTopic(topic)).as("вопрос дерева — без владельца (ADR-0036)").isEqualTo(2);
    }

    /** Оба поставили «не владеет» на одной паре — у каждого на экране Владения один пробел, свой. */
    @Test
    void twoTeachersMarkNotMasteredOnTheSamePairAndEachSeesOnlyTheirOwnGap() {
        StudentId bobsStudent = students.create(bob.id(), TestLibrary.unique("Сидорова Анна"));
        AssignmentId bobsAssignment = issue(bob, bobsStudent);
        receive(bob, bobsAssignment);
        Browser alicesBrowser = loggedIn(alice);
        alicesBrowser.postForm("/mastery", form(alicesAssignment, "NOT_MASTERED"));
        bobsBrowser.postForm("/mastery", form(bobsAssignment, "NOT_MASTERED"));

        String alicesScreen = alicesBrowser.get("/mastery?student=" + alicesStudent.value()).body();
        String bobsScreen = bobsBrowser.get("/mastery?student=" + bobsStudent.value()).body();

        assertThat(alicesScreen).as("у А один пробел").containsOnlyOnce("Подобрать задачи");
        assertThat(bobsScreen).as("и у Б один пробел, свой").containsOnlyOnce("Подобрать задачи");
    }

    private AssignmentId issue(TestAccounts.Account owner, StudentId student) {
        LocalDate today = LocalDate.now();
        return assignments.create(owner.id(), student, null, today, today.plusDays(7), TheoryScope.NONE,
                List.of(problem));
    }

    private void receive(TestAccounts.Account owner, AssignmentId assignment) {
        works.create(owner.id(), assignment, problem, LocalDate.now(), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }

    private List<Map.Entry<String, String>> form(AssignmentId assignment, String status) {
        return List.of(
                Map.entry("assignment", String.valueOf(assignment.value())),
                Map.entry("problem", String.valueOf(problem.value())),
                Map.entry("topic", String.valueOf(topic.value())),
                Map.entry("method", String.valueOf(method.value())),
                Map.entry("status", status));
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
