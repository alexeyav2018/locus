package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.FoundProblem;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupNotFoundException;
import ru.locus.student.GroupRepository;
import ru.locus.student.GroupService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentNotFoundException;
import ru.locus.student.StudentRepository;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Задача 5.2: правила выдачи, показа, переноса срока и удаления Заданий.
 *
 * Как и в {@link ru.locus.student.StudentServiceTest}, правила проверяются
 * на сервисе, а не через экран, и вошедший — настоящая учётная запись:
 * сервису личного контура нужен {@code UserId} владельца. Чужое заводится
 * напрямую через репозитории от имени второго Учителя.
 *
 * «Не сдано» и теория проверяются отдельно — {@code NotSubmittedTest}
 * и {@code AssignmentTheoryTest}; заморозка библиотеки —
 * {@code AssignmentsFreezeTheLibraryTest}.
 */
class AssignmentServiceTest extends IntegrationTest {

    @Autowired
    private AssignmentService service;

    @Autowired
    private AssignmentRepository repository;

    @Autowired
    private AssignmentBatchRepository batchRepository;

    @Autowired
    private StudentService students;

    @Autowired
    private GroupService groups;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private TestAccounts.Account alice;
    private TestAccounts.Account bob;

    @BeforeEach
    void logInAsATeacher() {
        alice = accounts.settled(Role.TEACHER);
        bob = accounts.settled(Role.TEACHER);
        LoggedIn.as(alice);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Выдача Ученику»: порядок выбора, срок, сегодняшняя дата, без Раздачи. */
    @Test
    void assignmentIsIssuedToAStudentInTheChosenOrder() {
        TaxonomyNodeId topic = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        LocalDate due = LocalDate.now().plusDays(7);

        AssignmentId id = service.issueToStudent(student, List.of(second, first), due, TheoryScope.TOPICS);

        ListedAssignment listed = service.assignment(id);
        assertThat(listed.assignment().student()).isEqualTo(student);
        assertThat(listed.assignment().problems()).containsExactly(second, first);
        assertThat(listed.assignment().dueDate()).isEqualTo(due);
        assertThat(listed.assignment().issuedOn()).isEqualTo(LocalDate.now());
        assertThat(listed.assignment().theoryScope()).isEqualTo(TheoryScope.TOPICS);
        assertThat(listed.assignment().batch()).as("выдано лично").isNull();
        assertThat(listed.studentName()).isEqualTo(students.student(student).name());
        assertThat(service.problemsOf(listed.assignment())).extracting(FoundProblem::number)
                .containsExactly(second.value(), first.value());
    }

    /** Сценарий «Выдача Группе из двенадцати» — признак готовности карточки. */
    @Test
    void issuingToAGroupOfTwelveCreatesTwelveAssignmentsUnderOneBatch() {
        TaxonomyNodeId topic = library.topic();
        List<ProblemId> problems = List.of(library.problem(topic), library.problem(topic), library.problem(topic));
        List<StudentId> members = IntStream.range(0, 12)
                .mapToObj(i -> students.create(TestLibrary.unique("Ученик " + i)))
                .toList();
        String groupName = TestLibrary.unique("9Б");
        GroupId group = groups.create(groupName);
        groups.setMembers(group, members);
        LocalDate due = LocalDate.now().plusDays(3);

        AssignmentBatchId batch = service.issueToGroup(group, problems, due, TheoryScope.NONE);

        assertThat(service.batch(batch).groupName()).isEqualTo(groupName);
        assertThat(service.batch(batch).issuedOn()).isEqualTo(LocalDate.now());
        List<ListedAssignment> issued = service.ofBatch(batch);
        assertThat(issued).hasSize(12);
        assertThat(issued).extracting(listed -> listed.assignment().student())
                .containsExactlyInAnyOrderElementsOf(members);
        assertThat(issued).allSatisfy(listed -> {
            assertThat(listed.assignment().problems()).isEqualTo(problems);
            assertThat(listed.assignment().dueDate()).isEqualTo(due);
            assertThat(listed.assignment().batch()).isEqualTo(batch);
        });
        assertThat(service.batches()).extracting(AssignmentBatch::id).contains(batch);
    }

    /** Сценарий «Пустая Группа»: ни Задания, ни Раздачи. */
    @Test
    void emptyGroupIsRefusedBeforeAnythingIsWritten() {
        ProblemId problem = library.problem(library.topic());
        GroupId group = groups.create(TestLibrary.unique("Пустая"));
        int batchesBefore = service.batches().size();

        assertThatThrownBy(() -> service.issueToGroup(group, List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пуста");

        assertThat(service.batches()).hasSize(batchesBefore);
        assertThat(service.list(AssignmentFilter.none())).isEmpty();
    }

    /** Сценарии «Задание без Задач» и «Задание без срока». */
    @Test
    void assignmentWithoutProblemsOrWithoutADueDateIsRefused() {
        StudentId student = students.create(TestLibrary.unique("Без задач"));
        ProblemId problem = library.problem(library.topic());

        assertThatThrownBy(() -> service.issueToStudent(student, List.of(), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна Задача");
        assertThatThrownBy(() -> service.issueToStudent(student, null, LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна Задача");
        assertThatThrownBy(() -> service.issueToStudent(student, List.of(problem), null, TheoryScope.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Срок");

        assertThat(service.list(AssignmentFilter.none())).isEmpty();
    }

    /** Сценарий «Несуществующая Задача»: отказ называет номер, Задание не создаётся. */
    @Test
    void missingProblemIsRefusedAndNamed() {
        StudentId student = students.create(TestLibrary.unique("Иванов"));
        ProblemId existing = library.problem(library.topic());
        ProblemId missing = new ProblemId(existing.value() + 1_000_000);

        assertThatThrownBy(() -> service.issueToStudent(student, List.of(existing, missing),
                LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(missing.value()))
                .hasMessageContaining("не существует");

        assertThat(service.list(AssignmentFilter.none())).isEmpty();
    }

    /** Сценарий «Повтор Задачи в выдаче»: один раз, порядок первого вхождения. */
    @Test
    void repeatedProblemIsKeptOnce() {
        TaxonomyNodeId topic = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);
        StudentId student = students.create(TestLibrary.unique("Иванов"));

        AssignmentId id = service.issueToStudent(student, List.of(first, second, first),
                LocalDate.now(), null);

        Assignment issued = service.assignment(id).assignment();
        assertThat(issued.problems()).containsExactly(first, second);
        assertThat(issued.theoryScope()).as("умолчание охвата — без теории").isEqualTo(TheoryScope.NONE);
    }

    /**
     * Сценарий «Выдача чужому Ученику»: чужой Ученик и чужая Группа
     * неотличимы от несуществующих, Заданий не создаётся.
     */
    @Test
    void foreignStudentAndGroupAreIndistinguishableFromMissingOnes() {
        ProblemId problem = library.problem(library.topic());
        StudentId bobsStudent = studentRepository.create(bob.id(), TestLibrary.unique("Ученик Боба"));
        GroupId bobsGroup = groupRepository.create(bob.id(), TestLibrary.unique("Группа Боба"));
        groupRepository.setMembers(bob.id(), bobsGroup, List.of(bobsStudent));
        StudentId missingStudent = new StudentId(bobsStudent.value() + 1_000_000);
        GroupId missingGroup = new GroupId(bobsGroup.value() + 1_000_000);

        assertThatThrownBy(() -> service.issueToStudent(bobsStudent, List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.issueToStudent(missingStudent, List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.issueToGroup(bobsGroup, List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(GroupNotFoundException.class);
        assertThatThrownBy(() -> service.issueToGroup(missingGroup, List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(GroupNotFoundException.class);

        assertThat(service.list(AssignmentFilter.none())).isEmpty();
        assertThat(repository.countByStudent(bob.id(), bobsStudent)).as("у Боба ничего не появилось").isZero();
    }

    /** Чужое Задание и чужая Раздача — как несуществующие: чтение, перенос срока, удаление. */
    @Test
    void foreignAssignmentAndBatchAreIndistinguishableFromMissingOnes() {
        ProblemId problem = library.problem(library.topic());
        StudentId bobsStudent = studentRepository.create(bob.id(), TestLibrary.unique("Ученик Боба"));
        LocalDate due = LocalDate.now();
        AssignmentBatchId bobsBatch = batchRepository.create(bob.id(), TestLibrary.unique("Группа Боба"), LocalDate.now());
        AssignmentId bobsAssignment = repository.create(bob.id(), bobsStudent, bobsBatch,
                LocalDate.now(), due, TheoryScope.NONE, List.of(problem));

        assertThatThrownBy(() -> service.assignment(bobsAssignment)).isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.changeDueDate(bobsAssignment, due.plusDays(1)))
                .isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.delete(bobsAssignment)).isInstanceOf(AssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.batch(bobsBatch)).isInstanceOf(AssignmentBatchNotFoundException.class);
        assertThatThrownBy(() -> service.ofBatch(bobsBatch)).isInstanceOf(AssignmentBatchNotFoundException.class);
        assertThatThrownBy(() -> service.deleteBatch(bobsBatch)).isInstanceOf(AssignmentBatchNotFoundException.class);

        Assignment untouched = repository.findById(bob.id(), bobsAssignment).orElseThrow();
        assertThat(untouched.dueDate()).isEqualTo(due);
        assertThat(service.list(AssignmentFilter.none())).isEmpty();
    }

    /** Сценарий «Перенос срока»: новый срок виден, остальное на месте. */
    @Test
    void dueDateIsMovedAndNothingElseChanges() {
        ProblemId problem = library.problem(library.topic());
        StudentId student = students.create(TestLibrary.unique("Иванов"));
        AssignmentId id = service.issueToStudent(student, List.of(problem), LocalDate.now(), TheoryScope.TOPICS);
        LocalDate moved = LocalDate.now().plusWeeks(1);

        service.changeDueDate(id, moved);

        Assignment after = service.assignment(id).assignment();
        assertThat(after.dueDate()).isEqualTo(moved);
        assertThat(after.problems()).containsExactly(problem);
        assertThat(after.theoryScope()).isEqualTo(TheoryScope.TOPICS);
        assertThatThrownBy(() -> service.changeDueDate(id, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Срок");
    }

    /** Сценарий «Удаление Задания без Работ»: Задание исчезает, Ученик и Задача остаются. */
    @Test
    void assignmentIsDeletedAndTheStudentAndProblemStay() {
        ProblemId problem = library.problem(library.topic());
        StudentId student = students.create(TestLibrary.unique("Иванов"));
        AssignmentId id = service.issueToStudent(student, List.of(problem), LocalDate.now(), TheoryScope.NONE);

        service.delete(id);

        assertThatThrownBy(() -> service.assignment(id)).isInstanceOf(AssignmentNotFoundException.class);
        assertThat(service.list(AssignmentFilter.none())).isEmpty();
        assertThat(students.student(student).id()).isEqualTo(student);
        assertThat(repository.countByProblem(problem)).isZero();
    }

    /** Сценарий «Удаление Раздачи»: все Задания и сама Раздача; Ученики на месте. */
    @Test
    void batchIsDeletedWithAllItsAssignments() {
        ProblemId problem = library.problem(library.topic());
        List<StudentId> members = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            members.add(students.create(TestLibrary.unique("Ученик " + i)));
        }
        GroupId group = groups.create(TestLibrary.unique("9В"));
        groups.setMembers(group, members);
        AssignmentBatchId batch = service.issueToGroup(group, List.of(problem), LocalDate.now(), TheoryScope.NONE);
        List<AssignmentId> issued = service.ofBatch(batch).stream().map(l -> l.assignment().id()).toList();
        assertThat(issued).hasSize(3);

        service.deleteBatch(batch);

        assertThatThrownBy(() -> service.batch(batch)).isInstanceOf(AssignmentBatchNotFoundException.class);
        issued.forEach(id -> assertThatThrownBy(() -> service.assignment(id))
                .isInstanceOf(AssignmentNotFoundException.class));
        assertThat(students.all()).extracting(s -> s.id()).containsAll(members);
        assertThat(service.batches()).extracting(AssignmentBatch::id).doesNotContain(batch);
    }

    /** Сценарий «По Ученику»: сводка по Ученику, включая выданные через Раздачу. */
    @Test
    void listByStudentIncludesAssignmentsIssuedThroughABatch() {
        ProblemId problem = library.problem(library.topic());
        StudentId student = students.create(TestLibrary.unique("Иванов"));
        StudentId other = students.create(TestLibrary.unique("Петров"));
        GroupId group = groups.create(TestLibrary.unique("9Г"));
        groups.setMembers(group, List.of(student, other));
        AssignmentBatchId batch = service.issueToGroup(group, List.of(problem), LocalDate.now().plusDays(2), TheoryScope.NONE);
        AssignmentId personal = service.issueToStudent(student, List.of(problem), LocalDate.now().plusDays(1), TheoryScope.NONE);

        List<ListedAssignment> ofStudent = service.list(new AssignmentFilter(student, null, null, null, false));

        assertThat(ofStudent).hasSize(2);
        assertThat(ofStudent).extracting(l -> l.assignment().student()).containsOnly(student);
        assertThat(ofStudent.getFirst().assignment().id()).as("ближайший срок первым").isEqualTo(personal);
        assertThat(service.list(new AssignmentFilter(null, batch, null, null, false))).hasSize(2);
        assertThat(service.list(AssignmentFilter.none())).hasSize(3);
    }

    /** Сценарий «Администратор без роли Учителя»: недоступно всё, включая чтение. */
    @Test
    void userWithoutTheTeacherRoleIsRefusedEverything() {
        ProblemId problem = library.problem(library.topic());
        StudentId student = students.create(TestLibrary.unique("Иванов"));
        AssignmentId id = service.issueToStudent(student, List.of(problem), LocalDate.now(), TheoryScope.NONE);
        Assignment issued = service.assignment(id).assignment();
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.issueToStudent(student, List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.issueToGroup(new GroupId(1), List.of(problem), LocalDate.now(), TheoryScope.NONE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.assignment(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(AssignmentFilter.none())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::batches).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.batch(new AssignmentBatchId(1))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.ofBatch(new AssignmentBatchId(1))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.problemsOf(issued)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.theoryOf(issued)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.changeDueDate(id, LocalDate.now())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteBatch(new AssignmentBatchId(1))).isInstanceOf(AccessDeniedException.class);
    }
}
