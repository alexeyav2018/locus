package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.TestLibrary.unique;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.mastery.Cell;
import ru.locus.mastery.Solved;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 1.4: хранение Разминок — запись читается обратно с участниками
 * по алфавиту и Задачами в порядке выбора, список отбирается по участнику
 * и идёт новыми первыми, а каждый метод отвечает только своему владельцу.
 *
 * Как в {@link ru.locus.assignment.AssignmentRepositoryTest}, изоляция
 * проверяется двумя владельцами. Сверх того — то, что держит схема:
 * чужой Ученик в участниках и итог не участнику или не по Задаче состава
 * не вставляются; пустая клетка — отсутствие строки; удаление Разминки
 * уносит итоги, а Ученики и Задачи остаются. Единственный метод без
 * владельца, {@code countByProblem}, считает Разминки ОБОИХ владельцев
 * (ADR-0036).
 */
class WarmupRepositoryTest extends IntegrationTest {

    private static final LocalDate HELD = LocalDate.of(2026, 10, 20);

    @Autowired
    private WarmupRepository warmups;

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
    void createdWarmupIsReadBackWithStudentsByNameAndProblemsInOrderOfChoice() {
        String prefix = unique("");
        StudentId zoya = students.create(alice, prefix + "Яковлева Зоя");
        StudentId anna = students.create(alice, prefix + "Антонова Анна");
        ProblemId third = library.problem(topic);
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        WarmupId id = warmups.create(alice, HELD, "9Б", List.of(zoya, anna), List.of(first, second, third));

        Warmup found = warmups.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.owner()).isEqualTo(alice);
        assertThat(found.heldOn()).isEqualTo(HELD);
        assertThat(found.groupName()).isEqualTo("9Б");
        assertThat(found.isForGroup()).isTrue();
        assertThat(found.students()).as("участники — по алфавиту имён").containsExactly(anna, zoya);
        assertThat(found.problems())
                .as("состав — в порядке выбора, а не по номерам Задач")
                .containsExactly(first, second, third);
    }

    @Test
    void warmupForAStudentHasNoGroupName() {
        WarmupId id = hold(alice, students.create(alice, unique("Иванов Пётр")), HELD);

        Warmup found = warmups.findById(alice, id).orElseThrow();
        assertThat(found.groupName()).isNull();
        assertThat(found.isForGroup()).isFalse();
    }

    @Test
    void anotherOwnerDoesNotSeeTheWarmup() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        WarmupId id = hold(alice, student, HELD);

        assertThat(warmups.findById(bob, id)).as("чужая Разминка неотличима от несуществующей").isEmpty();
        assertThat(warmups.find(bob, null)).extracting(Warmup::id).doesNotContain(id);
        assertThat(warmups.find(bob, student)).isEmpty();
        assertThat(warmups.findOutcomes(bob, id)).isEmpty();
        assertThat(warmups.countByStudent(bob, student)).isZero();
    }

    @Test
    void listIsNewestFirstAndFiltersByParticipant() {
        StudentId petr = students.create(alice, unique("Иванов Пётр"));
        StudentId anna = students.create(alice, unique("Антонова Анна"));
        WarmupId older = hold(alice, petr, HELD.minusDays(1));
        WarmupId newer = warmups.create(alice, HELD, "9Б", List.of(petr, anna), List.of(library.problem(topic)));
        WarmupId sameDayLater = hold(alice, anna, HELD);

        assertThat(warmups.find(alice, null)).extracting(Warmup::id)
                .as("новые по дате первыми, с одной датой — позже заведённая первой")
                .containsExactly(sameDayLater, newer, older);
        assertThat(warmups.find(alice, petr)).extracting(Warmup::id)
                .as("Разминки, в которых Ученик участвует, — и своя, и Группе")
                .containsExactly(newer, older);
        assertThat(warmups.countByStudent(alice, petr)).isEqualTo(2);
    }

    @Test
    void outcomeIsPutChangedAndRemovedAndAnEmptyCellIsNotStored() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);
        WarmupId id = warmups.create(alice, HELD, null, List.of(student), List.of(first, second));

        warmups.putOutcome(alice, id, student, first, WarmupOutcome.SUCCEEDED);
        warmups.putOutcome(alice, id, student, second, WarmupOutcome.SUCCEEDED);
        warmups.putOutcome(alice, id, student, second, WarmupOutcome.FAILED);

        assertThat(warmups.findOutcomes(alice, id)).containsExactly(Map.entry(student,
                Map.of(first, WarmupOutcome.SUCCEEDED, second, WarmupOutcome.FAILED)));

        warmups.removeOutcome(alice, id, student, first);

        assertThat(warmups.findOutcomes(alice, id).get(student))
                .as("пустая клетка — отсутствие итога")
                .containsOnlyKeys(second);
        assertThat(outcomeRows(id)).as("пустая клетка не хранится значением").isEqualTo(1);
    }

    @Test
    void anotherOwnerNeitherPutsNorRemovesAnOutcome() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId problem = library.problem(topic);
        WarmupId id = warmups.create(alice, HELD, null, List.of(student), List.of(problem));
        warmups.putOutcome(alice, id, student, problem, WarmupOutcome.SUCCEEDED);

        warmups.removeOutcome(bob, id, student, problem);
        warmups.putOutcome(bob, id, student, problem, WarmupOutcome.FAILED);

        assertThat(warmups.findOutcomes(alice, id))
                .containsExactly(Map.entry(student, Map.of(problem, WarmupOutcome.SUCCEEDED)));
    }

    /** Ученик другого владельца в участниках: ключу {@code fk_warmup_student_student} не на что сослаться. */
    @Test
    void warmupWithAStudentOfAnotherOwnerIsRefusedByTheSchema() {
        StudentId theirs = students.create(bob, unique("Сидорова Анна"));

        assertThatThrownBy(() -> hold(alice, theirs, HELD)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(warmups.countByStudent(bob, theirs)).isZero();
    }

    @Test
    void outcomeForANonParticipantOrAProblemOutsideTheWarmupIsRefusedByTheSchema() {
        StudentId participant = students.create(alice, unique("Иванов Пётр"));
        StudentId outsider = students.create(alice, unique("Петров Иван"));
        ProblemId inside = library.problem(topic);
        ProblemId outside = library.problem(topic);
        WarmupId id = warmups.create(alice, HELD, null, List.of(participant), List.of(inside));

        assertThatThrownBy(() -> warmups.putOutcome(alice, id, outsider, inside, WarmupOutcome.SUCCEEDED))
                .as("итог не участнику")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> warmups.putOutcome(alice, id, participant, outside, WarmupOutcome.SUCCEEDED))
                .as("итог по Задаче не из состава")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(outcomeRows(id)).isZero();
    }

    @Test
    void deletionTakesOutcomesAwayAndLeavesStudentsAndProblems() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        ProblemId problem = library.problem(topic);
        WarmupId id = warmups.create(alice, HELD, null, List.of(student), List.of(problem));
        warmups.putOutcome(alice, id, student, problem, WarmupOutcome.FAILED);

        warmups.delete(bob, id);
        assertThat(warmups.findById(alice, id)).as("чужой владелец не удаляет").isPresent();

        warmups.delete(alice, id);

        assertThat(warmups.findById(alice, id)).isEmpty();
        assertThat(outcomeRows(id)).as("итоги ушли каскадом").isZero();
        assertThat(students.findById(alice, student)).isPresent();
        assertThat(problems.findById(problem)).isPresent();
        assertThat(warmups.countByProblem(problem)).isZero();
    }

    @Test
    void countByProblemCountsWarmupsOfEveryOwner() {
        ProblemId problem = library.problem(topic);
        warmups.create(alice, HELD, null, List.of(students.create(alice, unique("Иванов Пётр"))), List.of(problem));
        warmups.create(bob, HELD, null, List.of(students.create(bob, unique("Сидорова Анна"))), List.of(problem));

        assertThat(warmups.countByProblem(problem)).isEqualTo(2);
        assertThat(warmups.countByProblem(library.problem(topic))).isZero();
    }

    /**
     * Справка по парам: итог по Задаче на двух Темах и двух Методах
     * считается на всех четырёх парах; пустая клетка не входит, чужой
     * Ученик — пусто.
     */
    @Test
    void outcomesAreCountedByPairsOfTheProblemMarkup() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        TaxonomyNodeId otherTopic = library.topic();
        SolutionMethodId method = library.method();
        SolutionMethodId otherMethod = library.method();
        ProblemId wide = library.problem(List.of(topic, otherTopic), List.of(method, otherMethod), List.of(),
                ExamPart.SECOND);
        ProblemId narrow = library.problem(topic, method);
        ProblemId untouched = library.problem(topic, method);
        WarmupId id = warmups.create(alice, HELD, null, List.of(student), List.of(wide, narrow, untouched));
        warmups.putOutcome(alice, id, student, wide, WarmupOutcome.SUCCEEDED);
        warmups.putOutcome(alice, id, student, narrow, WarmupOutcome.FAILED);

        assertThat(warmups.countOutcomesByPairs(alice, student)).containsOnly(
                Map.entry(new Cell(topic, method), new Solved(1, 2)),
                Map.entry(new Cell(topic, otherMethod), new Solved(1, 1)),
                Map.entry(new Cell(otherTopic, method), new Solved(1, 1)),
                Map.entry(new Cell(otherTopic, otherMethod), new Solved(1, 1)));
        assertThat(warmups.countOutcomesByPairs(bob, student)).as("чужой Ученик — пусто").isEmpty();
    }

    private WarmupId hold(UserId owner, StudentId student, LocalDate heldOn) {
        return warmups.create(owner, heldOn, null, List.of(student), List.of(library.problem(topic)));
    }

    private int outcomeRows(WarmupId id) {
        return database.sql("select count(*) from warmup_outcome where warmup_id = ?")
                .param(id.value())
                .query(Integer.class)
                .single();
    }
}
