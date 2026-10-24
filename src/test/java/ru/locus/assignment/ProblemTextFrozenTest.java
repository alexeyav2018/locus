package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.file.FileType;
import ru.locus.problem.ExamPart;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemInUseException;
import ru.locus.problem.ProblemService;
import ru.locus.problem.ProblemSlot;
import ru.locus.problem.UploadedFile;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Требование «Заморозка распространяется на текст» (design.md изменения
 * {@code problem-text}, Р6): правка текста, запись текста и замена Улики
 * выданной Задачи отклоняются с числом Заданий и путём «новой Задачей»,
 * а чтение текста — то, что отдаётся файлом {@code .md}, — заморозке
 * не подлежит. Здесь же — что править текст может только Администратор.
 *
 * Лежит рядом с {@link AssignmentsFreezeTheLibraryTest}, а не в пакете
 * Задач: заморозку даёт ответ Заданий {@link AssignmentsOfProblem}.
 */
class ProblemTextFrozenTest extends IntegrationTest {

    private static final String CONDITION = "Решите уравнение $x^2 = 9$.";
    private static final String SOLUTION = "Корни $x = \\pm 3$.";

    @Autowired
    private AssignmentService service;

    @Autowired
    private ProblemService problems;

    @Autowired
    private StudentService students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private TaxonomyNodeId topic;
    private StudentId student;

    @BeforeEach
    void logInAsATeacherWhoIsAlsoAnAdministrator() {
        LoggedIn.as(accounts.settled(Role.TEACHER, Role.ADMINISTRATOR));
        topic = library.topic();
        student = students.create(TestLibrary.unique("Сидоров Иван"));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарии «Текст выданной Задачи не правится» и «Текст выданной Задачи скачивается». */
    @Test
    void issuedProblemWithTextIsFrozenButItsTextIsStillRead() {
        ProblemId problem = withText();
        issue(problem);
        issue(problem);
        Problem current = problems.problem(problem);

        assertThatThrownBy(() -> problems.editText(problem, ProblemSlot.CONDITION, "Решите $x = 1$.", 0))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (2)")
                .hasMessageContaining("новой Задачей");
        assertThatThrownBy(() -> problems.replaceEvidence(problem, ProblemSlot.SOLUTION, pdf()))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (2)");

        assertThat(problems.problem(problem)).as("текст и PDF прежние").isEqualTo(current);
        assertThat(problems.problem(problem).text().of(ProblemSlot.CONDITION))
                .as("текст, отдаваемый файлом .md, читается")
                .isEqualTo(CONDITION);
    }

    /** Сценарий «Выданной Задаче без текста текст не записывается». */
    @Test
    void issuedProblemWithoutTextGetsNoText() {
        ProblemId problem = library.problem(topic);
        issue(problem);

        assertThatThrownBy(() -> problems.writeText(problem, CONDITION, SOLUTION))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (1)")
                .hasMessageContaining("новой Задачей");

        assertThat(problems.problem(problem).hasText()).as("Задача осталась без текста").isFalse();
    }

    /** Сценарий «Учитель не правит текст»: каждая правка текста — отказ роли. */
    @Test
    void teacherIsRefusedEveryTextChange() {
        ProblemId withText = withText();
        ProblemId withoutText = library.problem(topic);
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> problems.create(ExamPart.FIRST, List.of(topic), List.of(library.method()),
                List.of(), CONDITION, SOLUTION, null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> problems.editText(withText, ProblemSlot.SOLUTION, SOLUTION, 0))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> problems.writeText(withoutText, CONDITION, SOLUTION))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> problems.replaceEvidence(withText, ProblemSlot.CONDITION, pdf()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> problems.preview(CONDITION, SOLUTION))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(problems.problem(withText).text().of(ProblemSlot.SOLUTION))
                .as("читать текст Учителю можно")
                .isEqualTo(SOLUTION);
    }

    private ProblemId withText() {
        return problems.create(ExamPart.FIRST, List.of(topic), List.of(library.method()), List.of(),
                CONDITION, SOLUTION, null, null);
    }

    private void issue(ProblemId problem) {
        service.issueToStudent(student, List.of(problem), LocalDate.now(), TheoryScope.NONE);
    }

    private static UploadedFile pdf() {
        return new UploadedFile(TestLibrary.pdf(), FileType.PDF);
    }
}
