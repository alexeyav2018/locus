package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 3.2: хранение Заданий — запись с составом читается обратно
 * в порядке выдачи, отбор соединяет условия по «и» и считает период
 * включительно, а главное — каждый метод отвечает только своему владельцу.
 *
 * Как в {@link ru.locus.student.GroupRepositoryTest}, изоляция проверяется
 * на КАЖДОМ методе двумя владельцами. Сверх того — то, что держит схема,
 * а не Java: Задание чужому Ученику не вставляется — составному ключу
 * не на что сослаться; удаление Задания уносит состав, а Ученик и Задача
 * остаются. Единственный метод без владельца, {@code countByProblem},
 * считает Задания ОБОИХ владельцев — в этом и смысл ADR-0036.
 */
class AssignmentRepositoryTest extends IntegrationTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 1);
    private static final LocalDate DUE = ISSUED.plusDays(7);

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private AssignmentBatchRepository batches;

    @Autowired
    private StudentRepository students;

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private JdbcClient database;

    private UserId alice;
    private UserId bob;
    private TaxonomyNodeId topic;

    @BeforeEach
    void twoTeachersAndATopic() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        topic = library.topic();
    }

    @Test
    void createdAssignmentIsReadBackByItsOwnerWithProblemsInOrderOfIssue() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId third = library.problem(topic);
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        AssignmentId id = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.TOPICS,
                List.of(first, second, third));

        Assignment found = assignments.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.owner()).isEqualTo(alice);
        assertThat(found.student()).isEqualTo(student);
        assertThat(found.batch()).as("выдано лично — Раздачи нет").isNull();
        assertThat(found.issuedOn()).isEqualTo(ISSUED);
        assertThat(found.dueDate()).isEqualTo(DUE);
        assertThat(found.theoryScope()).isEqualTo(TheoryScope.TOPICS);
        assertThat(found.problems())
                .as("состав — в порядке выбора, а не по номерам Задач")
                .containsExactly(first, second, third);
    }

    @Test
    void assignmentFromABatchRemembersIt() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);

        AssignmentId id = assignments.create(alice, student, batch, ISSUED, DUE, TheoryScope.NONE,
                List.of(library.problem(topic)));

        assertThat(assignments.findById(alice, id).orElseThrow().batch()).isEqualTo(batch);
    }

    @Test
    void anotherOwnerDoesNotFindTheAssignment() {
        AssignmentId id = issue(alice, DUE);

        assertThat(assignments.findById(bob, id))
                .as("чужое Задание неотличимо от несуществующего")
                .isEmpty();
    }

    /**
     * Задание чужому Ученику не вставляется: строке {@code assignment}
     * с владельцем А и Учеником Б не на что сослаться по ключу
     * {@code fk_assignment_student}. Инвариант держит схема, а не сервис.
     */
    @Test
    void assignmentToAStudentOfAnotherOwnerIsRefusedByTheSchema() {
        StudentId theirs = students.create(bob, unique("Сидорова Анна"));

        assertThatThrownBy(() -> assignments.create(alice, theirs, null, ISSUED, DUE, TheoryScope.NONE,
                List.of(library.problem(topic))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(assignments.find(alice, theirs, null, null, null)).isEmpty();
        assertThat(assignments.countByStudent(bob, theirs)).as("у Ученика Заданий не появилось").isZero();
    }

    /** То же для Раздачи: ключ {@code fk_assignment_batch} не найдёт пары (id, владелец). */
    @Test
    void assignmentInABatchOfAnotherOwnerIsRefusedByTheSchema() {
        StudentId mine = students.create(alice, unique("Иванов Пётр"));
        AssignmentBatchId theirs = batches.create(bob, unique("9Б"), ISSUED);

        assertThatThrownBy(() -> assignments.create(alice, mine, theirs, ISSUED, DUE, TheoryScope.NONE,
                List.of(library.problem(topic))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(assignments.findByBatch(bob, theirs)).isEmpty();
    }

    @Test
    void listHoldsOnlyOwnAssignmentsNearestDueDateFirst() {
        AssignmentId later = issue(alice, DUE.plusDays(3));
        AssignmentId sooner = issue(alice, DUE);
        AssignmentId sameDay = issue(alice, DUE);
        AssignmentId theirs = issue(bob, DUE);

        List<AssignmentId> mine = assignments.find(alice, null, null, null, null).stream()
                .map(Assignment::id)
                .filter(id -> List.of(later, sooner, sameDay, theirs).contains(id))
                .toList();

        assertThat(mine)
                .as("ближайшие по сроку первыми, при одном сроке — по идентификатору; чужого нет")
                .containsExactly(sooner, sameDay, later);
    }

    @Test
    void listCarriesTheProblemsOfEveryAssignment() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId a = library.problem(topic);
        ProblemId b = library.problem(topic);
        AssignmentId two = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(b, a));
        AssignmentId one = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(a));

        List<Assignment> found = assignments.find(alice, student, null, null, null);

        assertThat(found).extracting(Assignment::id).containsExactly(two, one);
        assertThat(found.get(0).problems()).as("состав списка — тоже в порядке выдачи").containsExactly(b, a);
        assertThat(found.get(1).problems()).containsExactly(a);
    }

    @Test
    void listIsNarrowedByStudentBatchAndPeriodTogether() {
        StudentId ivanov = students.create(alice, unique("Иванов Пётр"));
        StudentId sidorova = students.create(alice, unique("Сидорова Анна"));
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);
        ProblemId problem = library.problem(topic);
        AssignmentId ivanovInBatch = assignments.create(alice, ivanov, batch, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId sidorovaInBatch = assignments.create(alice, sidorova, batch, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId ivanovAlone = assignments.create(alice, ivanov, null, ISSUED, DUE.plusDays(10), TheoryScope.NONE, List.of(problem));

        assertThat(assignments.find(alice, ivanov, null, null, null))
                .extracting(Assignment::id)
                .as("по Ученику — и личные, и из Раздачи")
                .containsExactly(ivanovInBatch, ivanovAlone);
        assertThat(assignments.find(alice, null, batch, null, null))
                .extracting(Assignment::id)
                .containsExactly(ivanovInBatch, sidorovaInBatch);
        assertThat(assignments.find(alice, ivanov, batch, null, null))
                .extracting(Assignment::id)
                .as("условия соединяются по «и»")
                .containsExactly(ivanovInBatch);
        assertThat(assignments.find(alice, ivanov, null, DUE.plusDays(1), null))
                .extracting(Assignment::id)
                .containsExactly(ivanovAlone);
    }

    /** Период — по сроку, обе границы включительно. */
    @Test
    void periodIncludesBothEnds() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId problem = library.problem(topic);
        AssignmentId before = assignments.create(alice, student, null, ISSUED, DUE.minusDays(1), TheoryScope.NONE, List.of(problem));
        AssignmentId from = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId between = assignments.create(alice, student, null, ISSUED, DUE.plusDays(1), TheoryScope.NONE, List.of(problem));
        AssignmentId to = assignments.create(alice, student, null, ISSUED, DUE.plusDays(2), TheoryScope.NONE, List.of(problem));
        AssignmentId after = assignments.create(alice, student, null, ISSUED, DUE.plusDays(3), TheoryScope.NONE, List.of(problem));

        assertThat(assignments.find(alice, student, null, DUE, DUE.plusDays(2)))
                .extracting(Assignment::id)
                .containsExactly(from, between, to)
                .doesNotContain(before, after);
    }

    @Test
    void anotherOwnerGetsAnEmptyListEvenByMyStudentAndBatch() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);
        assignments.create(alice, student, batch, ISSUED, DUE, TheoryScope.NONE, List.of(library.problem(topic)));

        assertThat(assignments.find(bob, student, null, null, null)).isEmpty();
        assertThat(assignments.find(bob, null, batch, null, null)).isEmpty();
        assertThat(assignments.findByBatch(bob, batch)).isEmpty();
    }

    /** Задания Раздачи — по алфавиту имён Учеников: «кто не сдал» читается как список Группы. */
    @Test
    void assignmentsOfABatchComeByStudentNames() {
        String mark = UUID.randomUUID().toString();
        StudentId sidorova = students.create(alice, "Сидорова " + mark);
        StudentId antonov = students.create(alice, "Антонов " + mark);
        StudentId ivanov = students.create(alice, "Иванов " + mark);
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);
        AssignmentBatchId other = batches.create(alice, unique("10А"), ISSUED);
        ProblemId problem = library.problem(topic);
        AssignmentId forSidorova = assignments.create(alice, sidorova, batch, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId forIvanov = assignments.create(alice, ivanov, batch, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId forAntonov = assignments.create(alice, antonov, batch, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        assignments.create(alice, antonov, other, ISSUED, DUE, TheoryScope.NONE, List.of(problem));

        assertThat(assignments.findByBatch(alice, batch))
                .extracting(Assignment::id)
                .containsExactly(forAntonov, forIvanov, forSidorova);
    }

    @Test
    void dueDateIsChangedAndNothingElse() {
        AssignmentId id = issue(alice, DUE);
        Assignment before = assignments.findById(alice, id).orElseThrow();

        assignments.changeDueDate(alice, id, DUE.plusDays(7));

        Assignment after = assignments.findById(alice, id).orElseThrow();
        assertThat(after.dueDate()).isEqualTo(DUE.plusDays(7));
        assertThat(after.problems()).isEqualTo(before.problems());
        assertThat(after.student()).isEqualTo(before.student());
        assertThat(after.issuedOn()).isEqualTo(before.issuedOn());
    }

    @Test
    void anotherOwnerCannotChangeTheDueDate() {
        AssignmentId id = issue(alice, DUE);

        assignments.changeDueDate(bob, id, DUE.plusDays(7));

        assertThat(assignments.findById(alice, id).orElseThrow().dueDate())
                .as("правка чужого Задания не меняет ни одной строки")
                .isEqualTo(DUE);
    }

    /** Удаление уносит состав (каскад от Задания), но не Ученика и не Задачу — на них ключи без каскада. */
    @Test
    void deletingTheAssignmentRemovesItsProblemsButKeepsTheStudentAndTheLibrary() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId problem = library.problem(topic);
        AssignmentId gone = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId kept = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));

        assignments.delete(alice, gone);

        assertThat(assignments.findById(alice, gone)).isEmpty();
        assertThat(assignments.findById(alice, kept)).isPresent();
        assertThat(compositionRows(gone)).as("состав ушёл вместе с Заданием").isZero();
        assertThat(students.findById(alice, student)).as("Ученик остаётся").isPresent();
        assertThat(problems.findById(problem)).as("Задача библиотеки остаётся").isPresent();
    }

    @Test
    void anotherOwnerCannotDeleteTheAssignment() {
        AssignmentId id = issue(alice, DUE);

        assignments.delete(bob, id);

        assertThat(assignments.findById(alice, id))
                .as("удаление чужого Задания не меняет ни одной строки")
                .isPresent();
    }

    @Test
    void deletingByBatchRemovesItsAssignmentsOnly() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);
        ProblemId problem = library.problem(topic);
        AssignmentId inBatch = assignments.create(alice, student, batch, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        AssignmentId alone = assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));

        assignments.deleteByBatch(alice, batch);

        assertThat(assignments.findById(alice, inBatch)).isEmpty();
        assertThat(assignments.findById(alice, alone)).as("личное Задание не затронуто").isPresent();
        assertThat(batches.findById(alice, batch)).as("сама Раздача — дело её репозитория").isPresent();
    }

    @Test
    void anotherOwnerCannotDeleteByBatch() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);
        AssignmentId id = assignments.create(alice, student, batch, ISSUED, DUE, TheoryScope.NONE,
                List.of(library.problem(topic)));

        assignments.deleteByBatch(bob, batch);

        assertThat(assignments.findById(alice, id)).isPresent();
    }

    @Test
    void countByStudentSeesOnlyTheOwnersAssignments() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId problem = library.problem(topic);
        assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
        assignments.create(alice, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));

        assertThat(assignments.countByStudent(alice, student)).isEqualTo(2);
        assertThat(assignments.countByStudent(bob, student)).as("чужой Ученик — ноль").isZero();
    }

    /**
     * Счёт по Задаче идёт по ВСЕМ владельцам: спрашивает библиотека,
     * и подставить владельца ей некого (ADR-0036). Наружу — только число.
     */
    @Test
    void countByProblemCountsAssignmentsOfEveryOwner() {
        ProblemId shared = library.problem(topic);
        ProblemId untouched = library.problem(topic);
        StudentId mine = students.create(alice, unique("Иванов Пётр"));
        StudentId theirs = students.create(bob, unique("Сидорова Анна"));
        assignments.create(alice, mine, null, ISSUED, DUE, TheoryScope.NONE, List.of(shared));
        assignments.create(bob, theirs, null, ISSUED, DUE, TheoryScope.NONE, List.of(shared, untouched));
        assignments.create(bob, theirs, null, ISSUED, DUE, TheoryScope.NONE, List.of(shared));

        assertThat(assignments.countByProblem(shared)).isEqualTo(3);
        assertThat(assignments.countByProblem(untouched)).isEqualTo(1);
        assertThat(assignments.countByProblem(library.problem(topic))).isZero();
    }

    private AssignmentId issue(UserId owner, LocalDate dueDate) {
        StudentId student = students.create(owner, unique("Иванов Пётр"));
        return assignments.create(owner, student, null, ISSUED, dueDate, TheoryScope.NONE,
                List.of(library.problem(topic)));
    }

    private int compositionRows(AssignmentId assignment) {
        return database.sql("select count(*) from assignment_problem where assignment_id = ?")
                .param(assignment.value())
                .query(Integer.class)
                .single();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
