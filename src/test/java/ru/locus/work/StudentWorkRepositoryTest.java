package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 3.1: хранение Работ — запись с файлами читается обратно в порядке
 * загрузки, а главное — каждый метод отвечает только своему владельцу.
 *
 * Как в {@link ru.locus.assignment.AssignmentRepositoryTest}, изоляция
 * проверяется на КАЖДОМ методе двумя владельцами. Сверх того — то, что
 * держит схема, а не Java: вторая Работа на паре «Задание × Задача»
 * не вставляется (ADR-0038); Работа по Задаче не из состава и по чужому
 * Заданию не вставляется — составным ключам не на что сослаться; удаление
 * Работы уносит строки файлов, а Задание и Ученик остаются.
 */
class StudentWorkRepositoryTest extends IntegrationTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 1);
    private static final LocalDate RECEIVED = ISSUED.plusDays(3);

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentRepository students;

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
    void createdWorkIsReadBackByItsOwnerWithFilesInUploadOrder() {
        Issued issued = issue(alice, 2);
        FileKey first = key("jpg");
        FileKey second = key("pdf");

        StudentWorkId id = works.create(alice, issued.assignment(), issued.problems().get(1), RECEIVED,
                Verdict.INCORRECT, "потерян корень", List.of(first, second));

        StudentWork found = works.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.owner()).isEqualTo(alice);
        assertThat(found.assignment()).isEqualTo(issued.assignment());
        assertThat(found.problem()).isEqualTo(issued.problems().get(1));
        assertThat(found.receivedOn()).isEqualTo(RECEIVED);
        assertThat(found.verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(found.note()).isEqualTo("потерян корень");
        assertThat(found.files()).extracting(StudentWorkFile::key).containsExactly(first, second);
        assertThat(found.files()).extracting(StudentWorkFile::position).containsExactly(1, 2);
    }

    @Test
    void workWithoutVerdictIsReadBackAsUnchecked() {
        Issued issued = issue(alice, 1);

        StudentWorkId id = works.create(alice, issued.assignment(), issued.problems().get(0), RECEIVED, null, "",
                List.of(key("jpg")));

        StudentWork found = works.findById(alice, id).orElseThrow();
        assertThat(found.verdict()).isNull();
        assertThat(found.isChecked()).isFalse();
    }

    @Test
    void anotherOwnerDoesNotFindTheWork() {
        StudentWorkId id = receive(alice, issue(alice, 1), 0);

        assertThat(works.findById(bob, id)).as("чужая Работа неотличима от несуществующей").isEmpty();
    }

    /** На паре «Задание × Задача» Работа одна — держит {@code uq_student_work_pair} (ADR-0038). */
    @Test
    void secondWorkOnTheSamePairIsRejectedBySchema() {
        Issued issued = issue(alice, 1);
        works.create(alice, issued.assignment(), issued.problems().get(0), RECEIVED, null, "", List.of(key("jpg")));

        assertThatThrownBy(() -> works.create(alice, issued.assignment(), issued.problems().get(0), RECEIVED, null, "",
                List.of(key("jpg"))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(works.findByAssignment(alice, issued.assignment())).hasSize(1);
    }

    /** Работа по Задаче не из состава — ключу {@code fk_student_work_assignment_problem} не на что сослаться. */
    @Test
    void workOnAProblemOutsideTheAssignmentIsRejectedBySchema() {
        Issued issued = issue(alice, 1);
        ProblemId stranger = library.problem(topic);

        assertThatThrownBy(() -> works.create(alice, issued.assignment(), stranger, RECEIVED, null, "", List.of(key("jpg"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Работа по чужому Заданию — ключу {@code fk_student_work_assignment} не на что сослаться. */
    @Test
    void workOnAnotherOwnersAssignmentIsRejectedBySchema() {
        Issued theirs = issue(bob, 1);

        assertThatThrownBy(() -> works.create(alice, theirs.assignment(), theirs.problems().get(0), RECEIVED, null, "",
                List.of(key("jpg"))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(works.findByAssignment(bob, theirs.assignment())).isEmpty();
    }

    @Test
    void worksOfAssignmentAreListedForTheOwnerOnly() {
        Issued issued = issue(alice, 2);
        StudentWorkId first = receive(alice, issued, 0);
        StudentWorkId second = receive(alice, issued, 1);
        receive(bob, issue(bob, 1), 0);

        assertThat(works.findByAssignment(alice, issued.assignment())).extracting(StudentWork::id)
                .containsExactly(first, second);
        assertThat(works.findByAssignment(bob, issued.assignment())).as("чужое Задание — пусто").isEmpty();
    }

    @Test
    void worksOfStudentAreNewestFirstAndOwnerOnly() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        Issued older = issue(alice, student, 1);
        Issued newer = issue(alice, student, 1);
        Issued sameDay = issue(alice, student, 1);
        StudentWorkId olderWork = works.create(alice, older.assignment(), older.problems().get(0), RECEIVED.minusDays(2),
                null, "", List.of(key("jpg")));
        StudentWorkId newerWork = works.create(alice, newer.assignment(), newer.problems().get(0), RECEIVED,
                null, "", List.of(key("jpg")));
        StudentWorkId sameDayWork = works.create(alice, sameDay.assignment(), sameDay.problems().get(0), RECEIVED,
                null, "", List.of(key("jpg")));

        assertThat(works.findByStudent(alice, student)).extracting(StudentWork::id)
                .as("новые первыми: по дате получения, в один день — по идентификатору убыв.")
                .containsExactly(sameDayWork, newerWork, olderWork);
        assertThat(works.findByStudent(bob, student)).as("чужой Ученик — пусто").isEmpty();
    }

    @Test
    void addedFilesContinueThePositions() {
        Issued issued = issue(alice, 1);
        FileKey first = key("jpg");
        StudentWorkId id = works.create(alice, issued.assignment(), issued.problems().get(0), RECEIVED, null, "",
                List.of(first));
        FileKey second = key("jpg");
        FileKey third = key("pdf");

        works.addFiles(alice, id, List.of(second, third));

        StudentWork found = works.findById(alice, id).orElseThrow();
        assertThat(found.files()).extracting(StudentWorkFile::key).containsExactly(first, second, third);
        assertThat(found.files()).extracting(StudentWorkFile::position).containsExactly(1, 2, 3);
    }

    @Test
    void anotherOwnerCannotAddFiles() {
        StudentWorkId id = receive(alice, issue(alice, 1), 0);

        assertThatThrownBy(() -> works.addFiles(bob, id, List.of(key("jpg"))))
                .as("строка файла держится ключом на student_work(id, user_id)")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(works.findById(alice, id).orElseThrow().files()).hasSize(1);
    }

    @Test
    void fileIsDeletedByTheOwnerOnly() {
        Issued issued = issue(alice, 1);
        FileKey kept = key("jpg");
        StudentWorkId id = works.create(alice, issued.assignment(), issued.problems().get(0), RECEIVED, null, "",
                List.of(key("jpg"), kept));
        StudentWorkFileId doomed = works.findById(alice, id).orElseThrow().files().get(0).id();

        works.deleteFile(bob, id, doomed);
        assertThat(works.findById(alice, id).orElseThrow().files()).as("чужой не удалил").hasSize(2);

        works.deleteFile(alice, id, doomed);
        assertThat(works.findById(alice, id).orElseThrow().files()).extracting(StudentWorkFile::key).containsExactly(kept);
    }

    @Test
    void verdictIsSetChangedAndClearedByTheOwnerOnly() {
        StudentWorkId id = receive(alice, issue(alice, 1), 0);

        works.setVerdict(bob, id, Verdict.CORRECT, "чужое");
        assertThat(works.findById(alice, id).orElseThrow().verdict()).as("чужой не поставил").isNull();

        works.setVerdict(alice, id, Verdict.INCORRECT, "потерян корень");
        StudentWork checked = works.findById(alice, id).orElseThrow();
        assertThat(checked.verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(checked.note()).isEqualTo("потерян корень");

        works.setVerdict(alice, id, null, "");
        assertThat(works.findById(alice, id).orElseThrow().isChecked()).as("вердикт снят — снова не проверена").isFalse();
    }

    /** Удаление уносит строки файлов каскадом; Задание и Ученик остаются — на них ключи без каскада. */
    @Test
    void deletionTakesFileRowsAndLeavesAssignmentAndStudent() {
        Issued issued = issue(alice, 1);
        StudentWorkId id = works.create(alice, issued.assignment(), issued.problems().get(0), RECEIVED, null, "",
                List.of(key("jpg"), key("jpg")));

        works.delete(bob, id);
        assertThat(works.findById(alice, id)).as("чужой не удалил").isPresent();

        works.delete(alice, id);

        assertThat(works.findById(alice, id)).isEmpty();
        assertThat(fileRows(id)).isZero();
        assertThat(assignments.findById(alice, issued.assignment())).isPresent();
        assertThat(students.findById(alice, issued.student())).isPresent();
    }

    @Test
    void assignmentsWithWorkAreOnlyThoseWithAWorkAndOnlyTheOwners() {
        Issued withWork = issue(alice, 2);
        Issued withoutWork = issue(alice, 1);
        Issued theirs = issue(bob, 1);
        receive(alice, withWork, 0);
        receive(alice, withWork, 1);
        receive(bob, theirs, 0);

        assertThat(works.assignmentsWithWork(alice,
                List.of(withWork.assignment(), withoutWork.assignment(), theirs.assignment())))
                .as("Задание с двумя Работами — один раз; без Работ и чужое — нет")
                .containsExactly(withWork.assignment());
        assertThat(works.assignmentsWithWork(alice, List.of())).isEmpty();
    }

    @Test
    void worksOfStudentAreCountedForTheOwnerOnly() {
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        Issued issued = issue(alice, student, 2);
        receive(alice, issued, 0);
        receive(alice, issued, 1);

        assertThat(works.countByStudent(alice, student)).isEqualTo(2);
        assertThat(works.countByStudent(bob, student)).as("чужой Ученик — ноль").isZero();
    }

    private Issued issue(UserId owner, int problemCount) {
        return issue(owner, students.create(owner, unique("Иванов Пётр")), problemCount);
    }

    private Issued issue(UserId owner, StudentId student, int problemCount) {
        List<ProblemId> problems = java.util.stream.IntStream.range(0, problemCount)
                .mapToObj(i -> library.problem(topic))
                .toList();
        AssignmentId assignment = assignments.create(owner, student, null, ISSUED, ISSUED.plusDays(7),
                TheoryScope.NONE, problems);
        return new Issued(assignment, student, problems);
    }

    private StudentWorkId receive(UserId owner, Issued issued, int problemIndex) {
        return works.create(owner, issued.assignment(), issued.problems().get(problemIndex), RECEIVED, null, "",
                List.of(key("jpg")));
    }

    private int fileRows(StudentWorkId work) {
        return database.sql("select count(*) from student_work_file where student_work_id = ?")
                .param(work.value())
                .query(Integer.class)
                .single();
    }

    private static FileKey key(String extension) {
        return new FileKey(UUID.randomUUID() + "." + extension);
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }

    private record Issued(AssignmentId assignment, StudentId student, List<ProblemId> problems) {
    }
}
