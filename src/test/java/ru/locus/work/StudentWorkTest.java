package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.assignment.AssignmentId;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.user.UserId;

/**
 * Задача 2.2: состав записи Работы и правила, без которых Работа
 * не существует ни в памяти, ни в базе.
 *
 * Владелец, Задание, Задача и дата получения обязательны; файлов хотя бы
 * один и в порядке загрузки (ADR-0015). Вердикт необязателен: Работа
 * без вердикта — законное состояние «не проверена», и это отсутствие
 * данных, а не флаг (ADR-0038). Ученика в записи нет — он у Задания.
 */
class StudentWorkTest {

    private static final StudentWorkId ID = new StudentWorkId(1);
    private static final UserId OWNER = new UserId(5);
    private static final AssignmentId ASSIGNMENT = new AssignmentId(3);
    private static final ProblemId PROBLEM = new ProblemId(21);
    private static final LocalDate RECEIVED = LocalDate.of(2026, 9, 12);
    private static final StudentWorkFile FIRST = new StudentWorkFile(new StudentWorkFileId(1), new FileKey("a.jpg"), 1);
    private static final StudentWorkFile SECOND = new StudentWorkFile(new StudentWorkFileId(2), new FileKey("b.jpg"), 2);

    @Test
    void workKeepsEverythingItWasGiven() {
        StudentWork work = new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, RECEIVED, Verdict.CORRECT, "  чисто  ",
                List.of(FIRST, SECOND));

        assertThat(work.id()).isEqualTo(ID);
        assertThat(work.owner()).isEqualTo(OWNER);
        assertThat(work.assignment()).isEqualTo(ASSIGNMENT);
        assertThat(work.problem()).isEqualTo(PROBLEM);
        assertThat(work.receivedOn()).isEqualTo(RECEIVED);
        assertThat(work.verdict()).isEqualTo(Verdict.CORRECT);
        assertThat(work.isChecked()).isTrue();
        assertThat(work.note()).as("края примечания обрезаются").isEqualTo("чисто");
        assertThat(work.hasNote()).isTrue();
        assertThat(work.files()).containsExactly(FIRST, SECOND);
    }

    /** Работа без вердикта — «не проверена»: законное состояние, а не ошибка. */
    @Test
    void workWithoutVerdictIsUnchecked() {
        StudentWork work = new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, RECEIVED, null, null, List.of(FIRST));

        assertThat(work.verdict()).isNull();
        assertThat(work.isChecked()).isFalse();
        assertThat(work.note()).as("null-примечание — пустая строка, а не null").isEmpty();
        assertThat(work.hasNote()).isFalse();
    }

    @Test
    void workNeedsOwnerAssignmentProblemAndDate() {
        assertThatThrownBy(() -> new StudentWork(ID, null, ASSIGNMENT, PROBLEM, RECEIVED, null, "", List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("владелец");
        assertThatThrownBy(() -> new StudentWork(ID, OWNER, null, PROBLEM, RECEIVED, null, "", List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Задание");
        assertThatThrownBy(() -> new StudentWork(ID, OWNER, ASSIGNMENT, null, RECEIVED, null, "", List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Задача");
        assertThatThrownBy(() -> new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, null, null, "", List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("дата получения");
    }

    /** Работа без файла не существует (ADR-0015, ADR-0038). */
    @Test
    void workNeedsAtLeastOneFile() {
        assertThatThrownBy(() -> new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, RECEIVED, null, "", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
        assertThatThrownBy(() -> new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, RECEIVED, null, "", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
    }

    /** Перемешанные страницы ничем себя не выдадут — запись отказывает, а не сортирует молча. */
    @Test
    void filesMustComeInUploadOrder() {
        assertThatThrownBy(() -> new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, RECEIVED, null, "", List.of(SECOND, FIRST)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("в порядке загрузки");
    }

    @Test
    void filesAreCopiedNotShared() {
        List<StudentWorkFile> files = new ArrayList<>(List.of(FIRST));
        StudentWork work = new StudentWork(ID, OWNER, ASSIGNMENT, PROBLEM, RECEIVED, null, "", files);

        files.add(SECOND);

        assertThat(work.files()).containsExactly(FIRST);
    }

    @Test
    void fileNeedsIdKeyAndPositivePosition() {
        assertThatThrownBy(() -> new StudentWorkFile(null, new FileKey("a.jpg"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StudentWorkFile(new StudentWorkFileId(1), null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StudentWorkFile(new StudentWorkFileId(1), new FileKey("a.jpg"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadedFileIsEmptyWithoutContent() {
        assertThat(new UploadedWorkFile(null, "image/jpeg").isEmpty()).isTrue();
        assertThat(new UploadedWorkFile(new byte[0], "image/jpeg").isEmpty()).isTrue();
        assertThat(new UploadedWorkFile(new byte[] {1}, "image/jpeg").isEmpty()).isFalse();
    }
}
