package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemUsage;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.2: ответ библиотеке «использована ли Задача» считает Задания
 * ВСЕХ Учителей и не зависит от того, кто вошёл, — в этом смысл ADR-0036.
 *
 * Вошедшего здесь нет намеренно: вопрос задаёт Администратор, у которого
 * личного контура нет, и ответ обязан быть тем же и без входа вовсе.
 * Ответчик берётся из контекста как реализация {@link ProblemUsage},
 * а не по классу: так проверяется и то, что {@code ProblemService}
 * получит его в свой список.
 */
class AssignmentsOfProblemTest extends IntegrationTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 1);
    private static final LocalDate DUE = ISSUED.plusDays(7);

    @Autowired
    private List<ProblemUsage> usages;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Test
    void assignmentsAnswerTheLibrary() {
        assertThat(usages)
                .as("область Заданий отвечает на вопрос библиотеки")
                .anySatisfy(usage -> assertThat(usage).isInstanceOf(AssignmentsOfProblem.class));
    }

    @Test
    void aProblemIssuedByTwoTeachersIsCountedForBoth() {
        LoggedIn.nobody();
        TaxonomyNodeId topic = library.topic();
        ProblemId shared = library.problem(topic);
        ProblemId untouched = library.problem(topic);
        UserId alice = accounts.settled(Role.TEACHER).id();
        UserId bob = accounts.settled(Role.TEACHER).id();
        issue(alice, shared);
        issue(bob, shared);
        issue(bob, shared);

        assertThat(answer().of(shared))
                .as("три Задания двух Учителей — и ни имени Ученика, ни Учителя")
                .contains("вошла в Задания (3)");
        assertThat(answer().of(untouched)).as("невыданная Задача свободна").isEmpty();
    }

    @Test
    void deletingTheLastAssignmentReleasesTheProblem() {
        LoggedIn.nobody();
        UserId alice = accounts.settled(Role.TEACHER).id();
        ProblemId problem = library.problem(library.topic());
        AssignmentId only = issue(alice, problem);

        assertThat(answer().of(problem)).isPresent();

        assignments.delete(alice, only);

        assertThat(answer().of(problem)).as("Задача, не оставшаяся ни в одном Задании, снова свободна").isEmpty();
    }

    private ProblemUsage answer() {
        return usages.stream()
                .filter(AssignmentsOfProblem.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private AssignmentId issue(UserId owner, ProblemId problem) {
        StudentId student = students.create(owner, "Иванов Пётр-" + UUID.randomUUID());
        return assignments.create(owner, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
    }
}
