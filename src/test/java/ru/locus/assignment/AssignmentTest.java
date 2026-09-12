package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Задача 2.2: состав записи Задания и правила, без которых Задание
 * не существует ни в памяти, ни в базе.
 *
 * Владелец обязателен (инвариант 11 domain-model.md), Ученик, даты
 * и охват — тоже; Задач хотя бы одна и без повторов, порядок выдачи
 * сохраняется. Раздача необязательна: выданное лично Задание —
 * законное состояние. Поля «сдано» в записи нет — это инвариант 12,
 * и на уровне базы его сторожит {@code NotSubmittedIsNotStoredTest}.
 */
class AssignmentTest {

    private static final AssignmentId ID = new AssignmentId(1);
    private static final UserId OWNER = new UserId(5);
    private static final StudentId STUDENT = new StudentId(3);
    private static final AssignmentBatchId BATCH = new AssignmentBatchId(9);
    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 10);
    private static final LocalDate DUE = LocalDate.of(2026, 9, 17);
    private static final List<ProblemId> PROBLEMS = List.of(new ProblemId(21), new ProblemId(7), new ProblemId(13));

    @Test
    void assignmentKeepsEverythingItWasGiven() {
        Assignment assignment = new Assignment(ID, OWNER, STUDENT, BATCH, ISSUED, DUE, TheoryScope.TOPICS, PROBLEMS);

        assertThat(assignment.id()).isEqualTo(ID);
        assertThat(assignment.owner()).isEqualTo(OWNER);
        assertThat(assignment.student()).isEqualTo(STUDENT);
        assertThat(assignment.batch()).isEqualTo(BATCH);
        assertThat(assignment.issuedOn()).isEqualTo(ISSUED);
        assertThat(assignment.dueDate()).isEqualTo(DUE);
        assertThat(assignment.theoryScope()).isEqualTo(TheoryScope.TOPICS);
        assertThat(assignment.isFromBatch()).isTrue();
    }

    @Test
    void assignmentWithoutBatchIsIssuedPersonally() {
        Assignment assignment = new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, PROBLEMS);

        assertThat(assignment.batch()).isNull();
        assertThat(assignment.isFromBatch()).isFalse();
    }

    /**
     * Порядок Задач — то, что учитель выбрал; список без порядка
     * перемешался бы от чтения к чтению.
     */
    @Test
    void problemsKeepTheOrderOfIssue() {
        Assignment assignment = new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, PROBLEMS);

        assertThat(assignment.problems()).containsExactlyElementsOf(PROBLEMS);
    }

    @Test
    void problemsAreCopiedNotShared() {
        List<ProblemId> source = new ArrayList<>(PROBLEMS);
        Assignment assignment = new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, source);
        source.add(new ProblemId(99));

        assertThat(assignment.problems()).hasSize(3);
        assertThatThrownBy(() -> assignment.problems().add(new ProblemId(100)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void assignmentWithoutOwnerIsRefused() {
        assertThatThrownBy(() -> new Assignment(ID, null, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, PROBLEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("владелец");
    }

    @Test
    void assignmentWithoutStudentIsRefused() {
        assertThatThrownBy(() -> new Assignment(ID, OWNER, null, null, ISSUED, DUE, TheoryScope.NONE, PROBLEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ученик");
    }

    @Test
    void assignmentWithoutDatesIsRefused() {
        assertThatThrownBy(() -> new Assignment(ID, OWNER, STUDENT, null, null, DUE, TheoryScope.NONE, PROBLEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("дата выдачи");
        assertThatThrownBy(() -> new Assignment(ID, OWNER, STUDENT, null, ISSUED, null, TheoryScope.NONE, PROBLEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("срок");
    }

    @Test
    void assignmentWithoutTheoryScopeIsRefused() {
        assertThatThrownBy(() -> new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, null, PROBLEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("охват");
    }

    @Test
    void assignmentWithoutProblemsIsRefused() {
        assertThatThrownBy(() -> new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна Задача");
        assertThatThrownBy(() -> new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Повторы снимает сервис до выдачи; дошедший до записи повтор — ошибка
     * в коде, и запись отказывает, а не чинит молча.
     */
    @Test
    void repeatedProblemIsRefused() {
        List<ProblemId> repeated = List.of(new ProblemId(21), new ProblemId(7), new ProblemId(21));

        assertThatThrownBy(() -> new Assignment(ID, OWNER, STUDENT, null, ISSUED, DUE, TheoryScope.NONE, repeated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("21")
                .hasMessageContaining("повторяется");
    }

    /**
     * Поля «сдано» нет: «не сдано» вычисляется (инвариант 12, ADR-0016).
     * Появись оно в записи — тест назовёт лишний компонент.
     */
    @Test
    void recordCarriesNoSubmissionState() {
        assertThat(Assignment.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "owner", "student", "batch", "issuedOn", "dueDate", "theoryScope", "problems");
    }
}
