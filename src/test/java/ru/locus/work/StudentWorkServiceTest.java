package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentNotFoundException;
import ru.locus.assignment.AssignmentService;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileType;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.user.Role;

/**
 * Задача 5.1: правила приёма, вердикта, файлов и удаления Работы
 * (ADR-0015, ADR-0038) — на сервисе, с вошедшим Учителем.
 *
 * Хранилище здесь настоящее (локальное), но содержимое по ссылкам
 * не проверяется — это {@code WorkFilesTest}. Здесь — состав Работы после
 * каждого действия и отказы с текстом.
 */
class StudentWorkServiceTest extends IntegrationTest {

    @Autowired
    private StudentWorkService service;

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private StudentService students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TestClock clock;

    private StudentId student;
    private ProblemId first;
    private ProblemId second;
    private AssignmentId assignment;

    @BeforeEach
    void logInAndIssueAnAssignmentOfTwoProblems() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        student = students.create(TestLibrary.unique("Иванов Пётр"));
        first = library.problem(library.topic());
        second = library.problem(library.topic());
        assignment = assignments.issueToStudent(student, List.of(first, second), today().plusDays(7), TheoryScope.NONE);
    }

    @AfterEach
    void logOutAndResetTheClock() {
        clock.reset();
        LoggedIn.nobody();
    }

    /** Сценарий «Приём Работы с двумя снимками»: дата по умолчанию — сегодня по часам, вердикта нет. */
    @Test
    void receivedWorkHasBothFilesInOrderTodaysDateAndNoVerdict() {
        StudentWorkId id = service.receive(assignment, first, null, null, null, List.of(jpeg(), pdf()));

        StudentWork work = service.work(id);
        assertThat(work.assignment()).isEqualTo(assignment);
        assertThat(work.problem()).isEqualTo(first);
        assertThat(work.receivedOn()).isEqualTo(today());
        assertThat(work.isChecked()).isFalse();
        assertThat(work.note()).isEmpty();
        assertThat(work.files()).hasSize(2);
        assertThat(work.files().get(0).key().value()).as("снимок — JPEG после пережатия").endsWith(".jpg");
        assertThat(work.files().get(1).key().value()).as("PDF как есть").endsWith(".pdf");
        assertThat(service.ofAssignment(assignment)).containsOnlyKeys(first);
    }

    /** Сценарий «Приём с датой получения в прошлом». */
    @Test
    void receivedOnCanBeInThePast() {
        StudentWorkId id = service.receive(assignment, first, today().minusDays(2), null, "", List.of(jpeg()));

        assertThat(service.work(id).receivedOn()).isEqualTo(today().minusDays(2));
    }

    /** Сценарий «Работа без файла»: пустые поля формы — не файлы. */
    @Test
    void workWithoutFilesIsRefused() {
        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "",
                List.of(new UploadedWorkFile(new byte[0], FileType.JPEG))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
        assertThat(service.ofAssignment(assignment)).isEmpty();
    }

    /** Сценарий «Вторая Работа на той же Задаче Задания». */
    @Test
    void secondWorkOnTheSamePairIsRefused() {
        service.receive(assignment, first, null, null, "", List.of(jpeg()));

        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "", List.of(jpeg())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже принята")
                .hasMessageContaining("добавляются к ней");
        assertThat(service.ofAssignment(assignment).get(first).files()).hasSize(1);
    }

    /** Сценарий «Задача не из состава Задания». */
    @Test
    void problemOutsideTheAssignmentIsRefused() {
        ProblemId stranger = library.problem(library.topic());

        assertThatThrownBy(() -> service.receive(assignment, stranger, null, null, "", List.of(jpeg())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Такой Задачи в Задании нет");
        assertThat(service.ofAssignment(assignment)).isEmpty();
    }

    /** Сценарий «Приём после срока»: срок не мешает, Задание перестаёт быть несданным. */
    @Test
    void workIsReceivedAfterTheDueDateAndClearsNotSubmitted() {
        AssignmentId overdue = assignments.issueToStudent(student, List.of(first), today().minusDays(1), TheoryScope.NONE);
        assertThat(assignments.assignment(overdue).notSubmitted()).isTrue();

        service.receive(overdue, first, null, null, "", List.of(jpeg()));

        assertThat(assignments.assignment(overdue).notSubmitted()).isFalse();
    }

    /** Сценарии «Вердикт при приёме», «Вердикт позже приёма», «Смена вердикта» и снятие. */
    @Test
    void verdictIsSetAtReceiptLaterChangedAndCleared() {
        StudentWorkId checked = service.receive(assignment, first, null, Verdict.CORRECT, "чисто", List.of(jpeg()));
        assertThat(service.work(checked).verdict()).isEqualTo(Verdict.CORRECT);
        assertThat(service.work(checked).note()).isEqualTo("чисто");

        StudentWorkId later = service.receive(assignment, second, null, null, "", List.of(jpeg()));
        service.setVerdict(later, Verdict.INCORRECT, " потерян корень ");
        StudentWork work = service.work(later);
        assertThat(work.verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(work.note()).isEqualTo("потерян корень");
        assertThat(work.isChecked()).isTrue();

        service.setVerdict(later, Verdict.CORRECT, "");
        assertThat(service.work(later).verdict()).isEqualTo(Verdict.CORRECT);

        service.setVerdict(later, null, "посмотрю позже");
        assertThat(service.work(later).isChecked()).as("вердикт снят — «не проверена», примечание осталось").isFalse();
        assertThat(service.work(later).note()).isEqualTo("посмотрю позже");
    }

    /** Сценарий «Добавление файлов к принятой Работе»: в конец. */
    @Test
    void addedFilesGoAfterTheExistingOnes() {
        StudentWorkId id = service.receive(assignment, first, null, null, "", List.of(pdf()));

        service.addFiles(id, List.of(jpeg(), jpeg()));

        List<StudentWorkFile> files = service.work(id).files();
        assertThat(files).hasSize(3);
        assertThat(files.get(0).key().value()).endsWith(".pdf");
        assertThat(files).extracting(StudentWorkFile::position).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> service.addFiles(id, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
    }

    /** Сценарии «Удаление файла» и «Последний файл не удаляется». */
    @Test
    void fileIsDeletedExceptTheLastOne() {
        StudentWorkId id = service.receive(assignment, first, null, null, "", List.of(jpeg(), pdf()));
        StudentWorkFile doomed = service.work(id).files().get(0);

        service.deleteFile(id, doomed.id());
        List<StudentWorkFile> left = service.work(id).files();
        assertThat(left).hasSize(1);
        assertThat(left.get(0).key().value()).endsWith(".pdf");

        assertThatThrownBy(() -> service.deleteFile(id, left.get(0).id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Работа без файла не существует")
                .hasMessageContaining("целиком");
        assertThat(service.work(id).files()).hasSize(1);
        assertThatThrownBy(() -> service.deleteFile(id, new StudentWorkFileId(999_999)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Такого файла");
    }

    /** Сценарии «Удаление Работы» и «Повторный приём после удаления». */
    @Test
    void workIsDeletedAndThePairIsFreeAgain() {
        StudentWorkId id = service.receive(assignment, first, null, Verdict.INCORRECT, "", List.of(jpeg(), jpeg()));

        service.delete(id);

        assertThatThrownBy(() -> service.work(id)).isInstanceOf(StudentWorkNotFoundException.class);
        assertThat(service.ofAssignment(assignment)).isEmpty();
        assertThat(assignments.assignment(assignment).studentName()).as("Задание и Ученик на месте").isNotBlank();
        StudentWorkId again = service.receive(assignment, first, null, null, "", List.of(jpeg()));
        assertThat(service.work(again).isChecked()).isFalse();
    }

    /** Сценарий «Работы Ученика»: новые первыми. */
    @Test
    void worksOfStudentAreNewestFirst() {
        StudentWorkId older = service.receive(assignment, first, today().minusDays(3), null, "", List.of(jpeg()));
        StudentWorkId newer = service.receive(assignment, second, today(), null, "", List.of(jpeg()));

        assertThat(service.ofStudent(student)).extracting(StudentWork::id).containsExactly(newer, older);
    }

    /** Сценарии «Приём по чужому Заданию» и «Правка чужой Работы»: неотличимы от несуществующих. */
    @Test
    void anotherTeachersAssignmentAndWorkLookNonexistent() {
        StudentWorkId mine = service.receive(assignment, first, null, null, "", List.of(jpeg()));
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.receive(assignment, second, null, null, "", List.of(jpeg())))
                .isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.ofAssignment(assignment)).isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.work(mine)).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.setVerdict(mine, Verdict.CORRECT, "")).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.addFiles(mine, List.of(jpeg()))).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.deleteFile(mine, new StudentWorkFileId(1))).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.delete(mine)).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.ofStudent(student)).isInstanceOf(ru.locus.student.StudentNotFoundException.class);
    }

    /** Сценарий «Администратор без роли Учителя». */
    @Test
    void withoutTheTeacherRoleEveryOperationIsDenied() {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "", List.of(jpeg())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.ofAssignment(assignment)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.ofStudent(student)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.work(new StudentWorkId(1))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(new StudentWorkId(1))).isInstanceOf(AccessDeniedException.class);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static UploadedWorkFile jpeg() {
        return new UploadedWorkFile(WorkFilesTest.resource("rotated.jpg"), FileType.JPEG);
    }

    private static UploadedWorkFile pdf() {
        return new UploadedWorkFile(TestLibrary.pdf(), FileType.PDF);
    }
}
