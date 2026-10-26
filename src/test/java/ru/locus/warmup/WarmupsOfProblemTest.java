package ru.locus.warmup;

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
 * Задача 3.4: ответ библиотеке «использована ли Задача» считает Разминки
 * ВСЕХ Учителей и не зависит от того, кто вошёл (ADR-0036, ADR-0061).
 *
 * Вошедшего здесь нет намеренно, как в {@code AssignmentsOfProblemTest}:
 * вопрос задаёт Администратор, у которого личного контура нет. Ответчик
 * берётся из контекста как реализация {@link ProblemUsage} — так
 * проверяется и то, что {@code ProblemService} получит его в свой список.
 */
class WarmupsOfProblemTest extends IntegrationTest {

    private static final LocalDate HELD = LocalDate.of(2026, 10, 1);

    @Autowired
    private List<ProblemUsage> usages;

    @Autowired
    private WarmupRepository warmups;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Test
    void warmupsAnswerTheLibrary() {
        assertThat(usages)
                .as("область Разминок отвечает на вопрос библиотеки")
                .anySatisfy(usage -> assertThat(usage).isInstanceOf(WarmupsOfProblem.class));
    }

    @Test
    void aProblemWarmedUpByTwoTeachersIsCountedForBoth() {
        LoggedIn.nobody();
        TaxonomyNodeId topic = library.topic();
        ProblemId shared = library.problem(topic);
        ProblemId untouched = library.problem(topic);
        UserId alice = accounts.settled(Role.TEACHER).id();
        UserId bob = accounts.settled(Role.TEACHER).id();
        hold(alice, shared);
        hold(bob, shared);

        assertThat(answer().of(shared))
                .as("две Разминки двух Учителей — и ни имени Ученика, ни Учителя")
                .contains("вошла в Разминки (2)");
        assertThat(answer().of(untouched)).as("Задача вне Разминок свободна").isEmpty();
    }

    @Test
    void deletingTheLastWarmupReleasesTheProblem() {
        LoggedIn.nobody();
        UserId alice = accounts.settled(Role.TEACHER).id();
        ProblemId problem = library.problem(library.topic());
        WarmupId only = hold(alice, problem);

        assertThat(answer().of(problem)).isPresent();

        warmups.delete(alice, only);

        assertThat(answer().of(problem)).as("Задача, не оставшаяся ни в одной Разминке, снова свободна").isEmpty();
    }

    private ProblemUsage answer() {
        return usages.stream()
                .filter(WarmupsOfProblem.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private WarmupId hold(UserId owner, ProblemId problem) {
        StudentId student = students.create(owner, "Иванов Пётр-" + UUID.randomUUID());
        return warmups.create(owner, HELD, null, List.of(student), List.of(problem));
    }
}
