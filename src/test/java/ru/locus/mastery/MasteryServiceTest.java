package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.NOT_MASTERED;
import static ru.locus.mastery.MasteryStatus.UNCERTAIN;
import static ru.locus.mastery.MasteryStatus.UNKNOWN;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentNotFoundException;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkId;
import ru.locus.work.StudentWorkRepository;
import ru.locus.work.Verdict;

/**
 * Задача 5.1: {@link MasteryService} — ячейки-кандидаты по Заданию
 * и выборочная простановка по принятой Работе.
 *
 * Ячейки — произведение Тем и Методов Задачи, и только у Задачи
 * с Работой (ADR-0011); значение по умолчанию — «неизвестно», которое
 * есть отсутствие строки (ADR-0039). Простановка меняет только названные
 * ячейки, перезаписывает без истории (ADR-0012), {@code UNKNOWN} снимает.
 * Пара не из разметки, Задача не из состава и Задача без Работы —
 * отказ, и ни одна ячейка не меняется. Вердикт на ячейки не влияет
 * (ADR-0014), удаление Работы отметку не снимает (ADR-0038). Справка
 * считает проверенные Работы Ученика по всем его Заданиям.
 */
class MasteryServiceTest extends IntegrationTest {

    @Autowired
    private MasteryService service;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;
    private StudentId student;
    private TaxonomyNodeId topicA;
    private TaxonomyNodeId topicB;
    private SolutionMethodId methodX;
    private SolutionMethodId methodY;
    private ProblemId problem;
    private ProblemId other;
    private AssignmentId assignment;

    @BeforeEach
    void logInAndIssueAProblemOfTwoTopicsAndTwoMethods() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
        student = students.create(owner, TestLibrary.unique("Иванов Пётр"));
        topicA = library.topic();
        topicB = library.topic();
        methodX = library.method();
        methodY = library.method();
        problem = library.problem(List.of(topicA, topicB), List.of(methodX, methodY), List.of(), ExamPart.SECOND);
        other = library.problem(topicA, methodX);
        assignment = issue(student, problem, other);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Ячейки у принятой Работы»: 2 × 2, все «неизвестно», в порядке разметки. */
    @Test
    void acceptedWorkGetsFourUnknownCellsInMarkupOrder() {
        receive(problem);

        Map<ProblemId, List<MasteryCell>> cells = service.cellsOf(assignment);

        assertThat(cells).containsOnlyKeys(problem);
        assertThat(cells.get(problem)).extracting(MasteryCell::cell).containsExactlyInAnyOrder(
                new Cell(topicA, methodX), new Cell(topicA, methodY),
                new Cell(topicB, methodX), new Cell(topicB, methodY));
        assertThat(cells.get(problem)).extracting(MasteryCell::topic)
                .as("Темы в порядке разметки, каждая со всеми Методами")
                .containsExactly(topicA, topicA, topicB, topicB);
        assertThat(cells.get(problem)).extracting(MasteryCell::status).containsOnly(UNKNOWN);
        assertThat(cells.get(problem)).extracting(MasteryCell::hint).containsOnly("решено 0 из 0");
        assertThat(cells.get(problem).get(0).topicPath()).isNotBlank();
        assertThat(cells.get(problem).get(0).methodName()).isNotBlank();
    }

    /** Сценарий «До приёма Работы ячеек нет». */
    @Test
    void withoutAWorkThereAreNoCells() {
        assertThat(service.cellsOf(assignment)).isEmpty();
    }

    /** Сценарий «Выборочная простановка»: две из четырёх меняются, две остаются. */
    @Test
    void marksOnlyTheNamedCells() {
        receive(problem);

        service.mark(assignment, problem, List.of(
                new Mark(topicA, methodX, MASTERED),
                new Mark(topicA, methodY, null),
                new Mark(topicB, methodX, NOT_MASTERED),
                new Mark(topicB, methodY, null)));

        assertThat(statuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new Cell(topicA, methodX), MASTERED, new Cell(topicA, methodY), UNKNOWN,
                new Cell(topicB, methodX), NOT_MASTERED, new Cell(topicB, methodY), UNKNOWN));
        assertThat(marks.countByStudent(owner, student)).isEqualTo(2);
    }

    /** Сценарий «Перезапись без истории». */
    @Test
    void overwritesWithoutHistory() {
        receive(problem);
        service.mark(assignment, problem, List.of(new Mark(topicA, methodX, MASTERED)));

        service.mark(assignment, problem, List.of(new Mark(topicA, methodX, NOT_MASTERED)));

        assertThat(statuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new Cell(topicA, methodX), NOT_MASTERED, new Cell(topicA, methodY), UNKNOWN,
                new Cell(topicB, methodX), UNKNOWN, new Cell(topicB, methodY), UNKNOWN));
        assertThat(marks.countByStudent(owner, student)).isEqualTo(1);
    }

    /** Сценарий «Возврат в „неизвестно“»: отметка снимается, суждений на одно меньше. */
    @Test
    void unknownWithdrawsTheMark() {
        receive(problem);
        service.mark(assignment, problem, List.of(
                new Mark(topicA, methodX, UNCERTAIN), new Mark(topicB, methodY, MASTERED)));

        service.mark(assignment, problem, List.of(new Mark(topicA, methodX, UNKNOWN)));

        assertThat(statuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new Cell(topicA, methodX), UNKNOWN, new Cell(topicA, methodY), UNKNOWN,
                new Cell(topicB, methodX), UNKNOWN, new Cell(topicB, methodY), MASTERED));
        assertThat(marks.countByStudent(owner, student)).isEqualTo(1);
    }

    /** Сценарий «Пара не из разметки Задачи»: отказ, и ничего не изменилось — даже законная отметка из того же списка. */
    @Test
    void pairOutsideTheMarkupIsRefusedAndNothingChanges() {
        receive(problem);
        TaxonomyNodeId strangeTopic = library.topic();

        assertThatThrownBy(() -> service.mark(assignment, problem, List.of(
                new Mark(topicA, methodX, MASTERED),
                new Mark(strangeTopic, methodX, MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пара не из разметки Задачи");
        assertThatThrownBy(() -> service.mark(assignment, problem, List.of(
                new Mark(topicA, library.method(), MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пара не из разметки Задачи");

        assertThat(statuses()).hasSize(4).containsValues(UNKNOWN).doesNotContainValue(MASTERED);
        assertThat(marks.countByStudent(owner, student)).isZero();
    }

    /** Пара из разметки ДРУГОЙ Задачи Задания — тоже не ячейка этой. */
    @Test
    void pairFromAnotherProblemOfTheAssignmentIsRefused() {
        receive(problem);
        receive(other);

        assertThatThrownBy(() -> service.mark(assignment, other, List.of(new Mark(topicB, methodY, MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пара не из разметки Задачи");
        assertThat(marks.countByStudent(owner, student)).isZero();
    }

    /** Задача не из состава Задания. */
    @Test
    void problemOutsideTheAssignmentIsRefused() {
        receive(problem);
        ProblemId stranger = library.problem(topicA, methodX);

        assertThatThrownBy(() -> service.mark(assignment, stranger, List.of(new Mark(topicA, methodX, MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Такой Задачи в Задании нет");
        assertThat(marks.countByStudent(owner, student)).isZero();
    }

    /** Сценарий «Работы по Задаче ещё нет». */
    @Test
    void problemWithoutAWorkIsRefused() {
        receive(other);

        assertThatThrownBy(() -> service.mark(assignment, problem, List.of(new Mark(topicA, methodX, MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("по принятой Работе");
        assertThat(marks.countByStudent(owner, student)).isZero();
    }

    /** Сценарии «Вердикт на отметки не влияет» и «Предзаполнения нет». */
    @Test
    void verdictDoesNotTouchTheCells() {
        StudentWorkId work = receive(problem);
        service.mark(assignment, problem, List.of(new Mark(topicA, methodX, NOT_MASTERED)));

        works.setVerdict(owner, work, Verdict.CORRECT, "");

        assertThat(statuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new Cell(topicA, methodX), NOT_MASTERED, new Cell(topicA, methodY), UNKNOWN,
                new Cell(topicB, methodX), UNKNOWN, new Cell(topicB, methodY), UNKNOWN));
    }

    /** Сценарий «Работа удалена — отметка осталась». */
    @Test
    void deletingTheWorkKeepsTheMark() {
        StudentWorkId work = receive(problem);
        service.mark(assignment, problem, List.of(new Mark(topicA, methodX, MASTERED)));

        works.delete(owner, work);

        assertThat(service.cellsOf(assignment)).as("ячеек без Работы нет").isEmpty();
        assertThat(marks.findByStudent(owner, student)).containsEntry(new Cell(topicA, methodX), MASTERED);
    }

    /** Сценарий «Справка считает проверенные Работы Ученика» — по другому Заданию того же Ученика. */
    @Test
    void hintCountsCheckedWorksOfTheStudentAcrossAssignments() {
        receive(problem);
        ProblemId p1 = library.problem(topicA, methodX);
        ProblemId p2 = library.problem(topicA, methodX);
        ProblemId p3 = library.problem(topicA, methodX);
        ProblemId p4 = library.problem(topicA, methodX);
        AssignmentId another = issue(student, p1, p2, p3, p4);
        works.setVerdict(owner, receive(another, p1), Verdict.CORRECT, "");
        works.setVerdict(owner, receive(another, p2), Verdict.INCORRECT, "");
        works.setVerdict(owner, receive(another, p3), Verdict.CORRECT, "");
        receive(another, p4);

        Map<Cell, String> hints = new HashMap<>();
        service.cellsOf(assignment).get(problem).forEach(cell -> hints.put(cell.cell(), cell.hint()));

        assertThat(hints.get(new Cell(topicA, methodX))).as("«A × X»").isEqualTo("решено 2 из 3");
        assertThat(hints.get(new Cell(topicA, methodY))).as("«A × Y» — без проверенных").isEqualTo("решено 0 из 0");
    }

    /** Сценарий «Простановка на чужом Задании»: неотличимо от несуществующего. */
    @Test
    void anotherTeachersAssignmentLooksNonexistent() {
        receive(problem);
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.cellsOf(assignment)).isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.mark(assignment, problem, List.of(new Mark(topicA, methodX, MASTERED))))
                .isInstanceOf(AssignmentNotFoundException.class);
        assertThat(marks.countByStudent(owner, student)).isZero();
    }

    /** Сценарий «Администратор без роли Учителя». */
    @Test
    void withoutTheTeacherRoleEveryOperationIsDenied() {
        receive(problem);
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.cellsOf(assignment)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.mark(assignment, problem, List.of(new Mark(topicA, methodX, MASTERED))))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Значения ячеек Задачи по ключу: порядок Методов у Задачи — по имени, а имена в тесте случайны. */
    private Map<Cell, MasteryStatus> statuses() {
        Map<Cell, MasteryStatus> statuses = new HashMap<>();
        service.cellsOf(assignment).get(problem).forEach(cell -> statuses.put(cell.cell(), cell.status()));
        return statuses;
    }

    private AssignmentId issue(StudentId student, ProblemId... problems) {
        return assignments.create(owner, student, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8),
                TheoryScope.NONE, List.of(problems));
    }

    private StudentWorkId receive(ProblemId problem) {
        return receive(assignment, problem);
    }

    private StudentWorkId receive(AssignmentId assignment, ProblemId problem) {
        return works.create(owner, assignment, problem, LocalDate.of(2026, 9, 3), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }
}
