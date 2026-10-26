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
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileType;
import ru.locus.mastery.Cell;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryRepository;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

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

    @Autowired
    private MasteryRepository marks;

    private UserId owner;
    private StudentId student;
    private ProblemId first;
    private ProblemId second;
    private AssignmentId assignment;

    @BeforeEach
    void logInAndIssueAnAssignmentOfTwoProblems() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        owner = account.id();
        LoggedIn.as(account);
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
        StudentWorkId id = service.receive(assignment, first, null, null, null, List.of(jpeg(), pdf()), List.of());

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
        StudentWorkId id = service.receive(assignment, first, today().minusDays(2), null, "", List.of(jpeg()), List.of());

        assertThat(service.work(id).receivedOn()).isEqualTo(today().minusDays(2));
    }

    /** Сценарий «Работа без файла»: пустые поля формы — не файлы. */
    @Test
    void workWithoutFilesIsRefused() {
        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "",
                List.of(new UploadedWorkFile(new byte[0], FileType.JPEG)), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("хотя бы один файл");
        assertThat(service.ofAssignment(assignment)).isEmpty();
    }

    /** Сценарий «Вторая Работа на той же Задаче Задания». */
    @Test
    void secondWorkOnTheSamePairIsRefused() {
        service.receive(assignment, first, null, null, "", List.of(jpeg()), List.of());

        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "", List.of(jpeg()), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже принята")
                .hasMessageContaining("добавляются к ней");
        assertThat(service.ofAssignment(assignment).get(first).files()).hasSize(1);
    }

    /** Сценарий «Задача не из состава Задания». */
    @Test
    void problemOutsideTheAssignmentIsRefused() {
        ProblemId stranger = library.problem(library.topic());

        assertThatThrownBy(() -> service.receive(assignment, stranger, null, null, "", List.of(jpeg()), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Такой Задачи в Задании нет");
        assertThat(service.ofAssignment(assignment)).isEmpty();
    }

    /** Сценарий «Приём после срока»: срок не мешает, Задание перестаёт быть несданным. */
    @Test
    void workIsReceivedAfterTheDueDateAndClearsNotSubmitted() {
        AssignmentId overdue = assignments.issueToStudent(student, List.of(first), today().minusDays(1), TheoryScope.NONE);
        assertThat(assignments.assignment(overdue).notSubmitted()).isTrue();

        service.receive(overdue, first, null, null, "", List.of(jpeg()), List.of());

        assertThat(assignments.assignment(overdue).notSubmitted()).isFalse();
    }

    /** Сценарии «Вердикт при приёме», «Вердикт позже приёма», «Смена вердикта» и снятие. */
    @Test
    void verdictIsSetAtReceiptLaterChangedAndCleared() {
        StudentWorkId checked = service.receive(assignment, first, null, Verdict.CORRECT, "чисто", List.of(jpeg()), List.of());
        assertThat(service.work(checked).verdict()).isEqualTo(Verdict.CORRECT);
        assertThat(service.work(checked).note()).isEqualTo("чисто");

        StudentWorkId later = service.receive(assignment, second, null, null, "", List.of(jpeg()), List.of());
        service.setVerdict(later, Verdict.INCORRECT, " потерян корень ", List.of());
        StudentWork work = service.work(later);
        assertThat(work.verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(work.note()).isEqualTo("потерян корень");
        assertThat(work.isChecked()).isTrue();

        service.setVerdict(later, Verdict.CORRECT, "", List.of());
        assertThat(service.work(later).verdict()).isEqualTo(Verdict.CORRECT);

        service.setVerdict(later, null, "посмотрю позже", List.of());
        assertThat(service.work(later).isChecked()).as("вердикт снят — «не проверена», примечание осталось").isFalse();
        assertThat(service.work(later).note()).isEqualTo("посмотрю позже");
    }

    /** Сценарий «Приём с отметкой»: Работа и отметка записаны, «без изменения» ячейку не трогает. */
    @Test
    void receiptWritesTheWorkAndItsMarks() {
        Marked marked = marked();

        service.receive(marked.assignment(), marked.problem(), null, Verdict.CORRECT, "", List.of(jpeg()), List.of(
                new Mark(marked.topic(), marked.x(), MasteryStatus.MASTERED),
                new Mark(marked.topic(), marked.y(), null)));

        assertThat(service.ofAssignment(marked.assignment())).containsOnlyKeys(marked.problem());
        assertThat(marks.findByStudent(owner, student))
                .as("«без изменения» строки не даёт")
                .containsExactlyEntriesOf(Map.of(new Cell(marked.topic(), marked.x()), MasteryStatus.MASTERED));
    }

    /** Сценарий «Приём без файла не пишет отметок». */
    @Test
    void refusedReceiptWritesNoMarks() {
        Marked marked = marked();

        assertThatThrownBy(() -> service.receive(marked.assignment(), marked.problem(), null, null, "", List.of(),
                List.of(new Mark(marked.topic(), marked.x(), MasteryStatus.MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Нужен хотя бы один файл");

        assertThat(service.ofAssignment(marked.assignment())).isEmpty();
        assertThat(marks.findByStudent(owner, student)).isEmpty();
    }

    /** Сценарий «Отказ в отметке отменяет приём»: пара не из разметки — ни Работы, ни отметок. */
    @Test
    void refusedMarkUndoesTheReceipt() {
        Marked marked = marked();
        SolutionMethodId stranger = library.method();

        assertThatThrownBy(() -> service.receive(marked.assignment(), marked.problem(), null, Verdict.CORRECT, "",
                List.of(jpeg()), List.of(
                        new Mark(marked.topic(), marked.x(), MasteryStatus.MASTERED),
                        new Mark(marked.topic(), stranger, MasteryStatus.MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ячейки нет");

        assertThat(service.ofAssignment(marked.assignment())).as("Работа откачена").isEmpty();
        assertThat(marks.findByStudent(owner, student)).isEmpty();
    }

    /** Сценарий «Вердикт и отметки одним сохранением». */
    @Test
    void verdictAndMarksAreSavedTogether() {
        Marked marked = marked();
        StudentWorkId id = service.receive(marked.assignment(), marked.problem(), null, null, "", List.of(jpeg()), List.of());

        service.setVerdict(id, Verdict.INCORRECT, "потерян корень", List.of(
                new Mark(marked.topic(), marked.x(), MasteryStatus.NOT_MASTERED),
                new Mark(marked.topic(), marked.y(), null)));

        assertThat(service.work(id).verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(marks.findByStudent(owner, student))
                .containsExactlyEntriesOf(Map.of(new Cell(marked.topic(), marked.x()), MasteryStatus.NOT_MASTERED));
    }

    /** Сценарий «Отказ в отметке не меняет вердикта». */
    @Test
    void refusedMarkKeepsTheVerdict() {
        Marked marked = marked();
        StudentWorkId id = service.receive(marked.assignment(), marked.problem(), null, null, "", List.of(jpeg()), List.of());
        SolutionMethodId stranger = library.method();

        assertThatThrownBy(() -> service.setVerdict(id, Verdict.CORRECT, "чисто", List.of(
                new Mark(marked.topic(), marked.x(), MasteryStatus.MASTERED),
                new Mark(marked.topic(), stranger, MasteryStatus.MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ячейки нет");

        assertThat(service.work(id).isChecked()).as("вердикт прежний — не проверена").isFalse();
        assertThat(service.work(id).note()).isEmpty();
        assertThat(marks.findByStudent(owner, student)).isEmpty();
    }

    /** Сценарий «Добавление файлов к принятой Работе»: в конец. */
    @Test
    void addedFilesGoAfterTheExistingOnes() {
        StudentWorkId id = service.receive(assignment, first, null, null, "", List.of(pdf()), List.of());

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
        StudentWorkId id = service.receive(assignment, first, null, null, "", List.of(jpeg(), pdf()), List.of());
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
        StudentWorkId id = service.receive(assignment, first, null, Verdict.INCORRECT, "", List.of(jpeg(), jpeg()), List.of());

        service.delete(id);

        assertThatThrownBy(() -> service.work(id)).isInstanceOf(StudentWorkNotFoundException.class);
        assertThat(service.ofAssignment(assignment)).isEmpty();
        assertThat(assignments.assignment(assignment).studentName()).as("Задание и Ученик на месте").isNotBlank();
        StudentWorkId again = service.receive(assignment, first, null, null, "", List.of(jpeg()), List.of());
        assertThat(service.work(again).isChecked()).isFalse();
    }

    /** Сценарий «Работы Ученика»: новые первыми. */
    @Test
    void worksOfStudentAreNewestFirst() {
        StudentWorkId older = service.receive(assignment, first, today().minusDays(3), null, "", List.of(jpeg()), List.of());
        StudentWorkId newer = service.receive(assignment, second, today(), null, "", List.of(jpeg()), List.of());

        assertThat(service.ofStudent(student)).extracting(StudentWork::id).containsExactly(newer, older);
    }

    /** Сценарии «Приём по чужому Заданию» и «Правка чужой Работы»: неотличимы от несуществующих. */
    @Test
    void anotherTeachersAssignmentAndWorkLookNonexistent() {
        StudentWorkId mine = service.receive(assignment, first, null, null, "", List.of(jpeg()), List.of());
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.receive(assignment, second, null, null, "", List.of(jpeg()), List.of()))
                .isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.ofAssignment(assignment)).isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.work(mine)).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.setVerdict(mine, Verdict.CORRECT, "", List.of())).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.addFiles(mine, List.of(jpeg()))).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.deleteFile(mine, new StudentWorkFileId(1))).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.delete(mine)).isInstanceOf(StudentWorkNotFoundException.class);
        assertThatThrownBy(() -> service.ofStudent(student)).isInstanceOf(ru.locus.student.StudentNotFoundException.class);
    }

    /** Сценарий «Администратор без роли Учителя». */
    @Test
    void withoutTheTeacherRoleEveryOperationIsDenied() {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.receive(assignment, first, null, null, "", List.of(jpeg()), List.of()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.ofAssignment(assignment)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.ofStudent(student)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.work(new StudentWorkId(1))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(new StudentWorkId(1))).isInstanceOf(AccessDeniedException.class);
    }

    /** Задание из одной Задачи с Темой и двумя Методами — две ячейки «Тема × X», «Тема × Y». */
    private Marked marked() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId x = library.method();
        SolutionMethodId y = library.method();
        ProblemId problem = library.problem(List.of(topic), List.of(x, y), List.of(), ExamPart.SECOND);
        AssignmentId issued = assignments.issueToStudent(student, List.of(problem), today().plusDays(7), TheoryScope.NONE);
        return new Marked(issued, problem, topic, x, y);
    }

    private record Marked(AssignmentId assignment, ProblemId problem, TaxonomyNodeId topic,
                          SolutionMethodId x, SolutionMethodId y) {
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
