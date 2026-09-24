package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
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
import ru.locus.problem.ProblemRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkRepository;

/**
 * Экран Владения одного Ученика (`mastery-views`): дерево с четырьмя
 * числами у узла с ячейками и «ячеек нет» у узла без них, отсутствие
 * форм и полей выбора (ADR-0011), ссылка пробела в поиск по паре
 * и ссылка на экран с карточки Ученика.
 */
class MasteryOverviewScreenTest extends IntegrationTest {

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
    private ProblemRepository problems;

    private UserId owner;
    private Browser teacher;
    private StudentId student;
    private TaxonomyNodeId topic;
    private TaxonomyNodeId emptySection;
    private SolutionMethodId masteredMethod;
    private SolutionMethodId gapMethod;
    private ProblemId gapProblem;

    @BeforeEach
    void logInAndMarkOneMasteredAndOneNotMasteredCell() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        owner = account.id();
        teacher = new Browser(port);
        teacher.logIn(account.login(), account.password());
        student = students.create(owner, TestLibrary.unique("Иванов Пётр"));

        topic = library.topic();
        emptySection = library.section();
        masteredMethod = library.method();
        gapMethod = library.method();
        ProblemId masteredProblem = library.problem(topic, masteredMethod);
        gapProblem = library.problem(topic, gapMethod);

        AssignmentId assignment = assignments.create(owner, student, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), TheoryScope.NONE,
                List.of(masteredProblem, gapProblem));
        receive(assignment, masteredProblem);
        receive(assignment, gapProblem);

        teacher.postForm("/mastery", List.of(
                java.util.Map.entry("assignment", String.valueOf(assignment.value())),
                java.util.Map.entry("problem", String.valueOf(masteredProblem.value())),
                java.util.Map.entry("topic", String.valueOf(topic.value())),
                java.util.Map.entry("method", String.valueOf(masteredMethod.value())),
                java.util.Map.entry("status", "MASTERED")));
        teacher.postForm("/mastery", List.of(
                java.util.Map.entry("assignment", String.valueOf(assignment.value())),
                java.util.Map.entry("problem", String.valueOf(gapProblem.value())),
                java.util.Map.entry("topic", String.valueOf(topic.value())),
                java.util.Map.entry("method", String.valueOf(gapMethod.value())),
                java.util.Map.entry("status", "NOT_MASTERED")));
    }

    @Test
    void topicShowsFourNumbersAndEmptySectionShowsNoCells() {
        String body = teacher.get(screen()).body();

        assertThat(body).contains("владеет 1 · владеет неуверенно 0 · не владеет 1 · неизвестно 0");
        assertThat(body).contains("ячеек нет");
    }

    @Test
    void pageHasNoFormsOrSelects() {
        String body = teacher.get(screen()).body();

        assertThat(body).doesNotContain("<form").doesNotContain("<select");
    }

    @Test
    void gapLinkLeadsToTheProblemMarkedWithThatPair() {
        String body = teacher.get(screen()).body();
        String href = "/problems?node=" + topic.value() + "&method=" + gapMethod.value();
        assertThat(body).contains("/problems?node=" + topic.value() + "&amp;method=" + gapMethod.value());

        String found = teacher.get(href).body();
        assertThat(found).contains("№ " + problems.findById(gapProblem).orElseThrow().number());
    }

    @Test
    void studentCardLinksToTheScreen() {
        String body = teacher.get("/students/" + student.value()).body();

        assertThat(body).contains("/mastery?student=" + student.value());
    }

    @Test
    void screenWithoutAStudentParameterRedirectsToStudents() {
        Browser.Page page = teacher.get("/mastery");

        assertThat(page.redirectsTo("/students")).isTrue();
    }

    private String screen() {
        return "/mastery?student=" + student.value();
    }

    private void receive(AssignmentId assignment, ProblemId problem) {
        works.create(owner, assignment, problem, LocalDate.of(2026, 9, 3), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }
}
