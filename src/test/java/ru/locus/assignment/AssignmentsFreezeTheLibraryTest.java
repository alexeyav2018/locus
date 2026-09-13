package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import ru.locus.problem.UploadedFile;
import ru.locus.student.StudentId;
import ru.locus.student.StudentInUseException;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Задача 5.5: первое настоящее срабатывание двух правил, до сих пор
 * выполнявшихся тождественно, — заморозки Задачи после выдачи (ADR-0030,
 * ответ {@link AssignmentsOfProblem}) и неудаляемости Ученика, на которого
 * ссылаются (ADR-0035, ответ {@link AssignmentsOfStudent}).
 *
 * Обе половины — на одной учётной записи с обеими ролями: выдаёт Учитель,
 * правит библиотеку Администратор, а правило должно срабатывать независимо
 * от того, кто из них спрашивает.
 *
 * Перестройка дерева заморозке не подчиняется (ADR-0034): выданная Задача
 * переезжает вместе с Темой. Что {@code rehomeTopic} не зовёт проверку,
 * сторожит {@code RestructureBypassesFreezeTest} по исходному тексту;
 * здесь — что это верно и на живых данных.
 */
class AssignmentsFreezeTheLibraryTest extends IntegrationTest {

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
    private ProblemId problem;
    private StudentId student;

    @BeforeEach
    void logInAsATeacherWhoIsAlsoAnAdministrator() {
        LoggedIn.as(accounts.settled(Role.TEACHER, Role.ADMINISTRATOR));
        topic = library.topic();
        problem = library.problem(topic);
        student = students.create(TestLibrary.unique("Иванов Пётр"));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Задача выдана — заморожена»: в отказе названы Задания и их число. */
    @Test
    void issuedProblemIsFrozenAgainstEveryHandEdit() {
        issue();
        issue();
        Problem current = problems.problem(problem);

        assertThatThrownBy(() -> problems.edit(problem, "Правится", current.part(),
                current.topics(), current.methods(), List.of()))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (2)");
        assertThatThrownBy(() -> problems.replaceCondition(problem, pdf()))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (2)");
        assertThatThrownBy(() -> problems.replaceSolution(problem, pdf()))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (2)");
        assertThatThrownBy(() -> problems.delete(problem))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (2)");

        assertThat(problems.problem(problem).caption()).as("ничего не изменилось").isEqualTo(current.caption());
    }

    /** Сценарий «Ученик с Заданием не удаляется»: в отказе названо число Заданий. */
    @Test
    void studentWithAnAssignmentIsNotDeleted() {
        issue();

        assertThatThrownBy(() -> students.delete(student))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("выдано Заданий (1)");

        assertThat(students.student(student).id()).isEqualTo(student);
    }

    /** Сценарий «Последнее Задание удалено»: и правка, и удаление снова проходят. */
    @Test
    void afterTheLastAssignmentIsDeletedEditingAndDeletingWorkAgain() {
        AssignmentId only = issue();
        Problem current = problems.problem(problem);

        service.delete(only);

        assertThatCode(() -> problems.edit(problem, "Снова правится", current.part(),
                current.topics(), current.methods(), List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> problems.replaceCondition(problem, pdf())).doesNotThrowAnyException();
        assertThatCode(() -> students.delete(student)).doesNotThrowAnyException();
        assertThatCode(() -> problems.delete(problem)).doesNotThrowAnyException();
    }

    /** Перестройка сильнее заморозки (ADR-0034): выданная Задача переезжает с Темой. */
    @Test
    void restructuringMovesAnIssuedProblemDespiteTheFreeze() {
        AssignmentId issued = issue();
        TaxonomyNodeId receiver = library.topic();

        assertThatCode(() -> problems.rehomeTopic(topic, receiver)).doesNotThrowAnyException();

        assertThat(problems.problem(problem).topics()).containsExactly(receiver);
        assertThat(service.assignment(issued).assignment().problems())
                .as("Задание по-прежнему ссылается на ту же Задачу")
                .containsExactly(problem);
    }

    private AssignmentId issue() {
        return service.issueToStudent(student, List.of(problem), LocalDate.now(), TheoryScope.NONE);
    }

    private static UploadedFile pdf() {
        return new UploadedFile(TestLibrary.pdf(), FileType.PDF);
    }
}
