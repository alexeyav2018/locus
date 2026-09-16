package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentBatchId;
import ru.locus.assignment.AssignmentFilter;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentInUseException;
import ru.locus.assignment.AssignmentService;
import ru.locus.assignment.ListedAssignment;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.1: вопрос {@link ru.locus.assignment.AssignmentWork} впервые
 * получает ответ — {@link WorksOfAssignment}, — и оба его следствия
 * впервые срабатывают по-настоящему.
 *
 * «Не сдано» — вторая половина условия, «Работы нет», которую
 * {@code NotSubmittedTest} проверить не мог: просроченное Задание
 * с Работой — сдано, и без вердикта тоже (ADR-0038). Отказ в удалении —
 * Задание с Работой не удаляется, Раздача с одним удержанным Заданием
 * отклоняется целиком (ADR-0037). После удаления Работы оба следствия
 * возвращаются: «не сдано» снова есть, удаление проходит.
 *
 * Работы кладутся репозиторием напрямую, а не сервисом Работ: здесь
 * проверяется ответчик, и хранилище файлов ему ни к чему.
 */
class AssignmentWorkAnsweredTest extends IntegrationTest {

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private StudentService students;

    @Autowired
    private GroupService groups;

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TestClock clock;

    private UserId owner;
    private StudentId student;
    private ProblemId problem;

    @BeforeEach
    void logInAsATeacher() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
        student = students.create(TestLibrary.unique("Иванов Пётр"));
        problem = library.problem(library.topic());
    }

    @AfterEach
    void logOutAndResetTheClock() {
        clock.reset();
        LoggedIn.nobody();
    }

    /** Сценарий «Задание с непроверенной Работой сдано». */
    @Test
    void overdueAssignmentWithAnUncheckedWorkIsNotNotSubmitted() {
        AssignmentId withWork = issue(today().minusDays(1));
        AssignmentId withoutWork = issue(today().minusDays(1));
        receive(withWork, null);

        assertThat(assignments.assignment(withWork).notSubmitted()).as("Работа есть — сдано, вердикт не нужен").isFalse();
        assertThat(assignments.assignment(withoutWork).notSubmitted()).isTrue();
        List<ListedAssignment> onlyNotSubmitted = assignments.list(new AssignmentFilter(null, null, null, null, true));
        assertThat(onlyNotSubmitted).extracting(listed -> listed.assignment().id())
                .as("в сводке «только несданные» Задания с Работой нет")
                .containsExactly(withoutWork);
    }

    /** Сценарий «Задание с Работой не удаляется». */
    @Test
    void assignmentWithAWorkIsNotDeleted() {
        AssignmentId id = issue(today());
        receive(id, Verdict.CORRECT);

        assertThatThrownBy(() -> assignments.delete(id))
                .isInstanceOf(AssignmentInUseException.class)
                .hasMessageContaining("уже есть Работа");
        assertThat(assignments.assignment(id)).isNotNull();
    }

    /** Сценарий «Удаление Раздачи, в которой одно Задание с Работой»: ни одно не удалено. */
    @Test
    void batchWithOneHeldAssignmentIsNotDeletedAtAll() {
        GroupId group = groups.create(TestLibrary.unique("9Б"));
        groups.setMembers(group, List.of(student,
                students.create(TestLibrary.unique("Сидорова Анна")),
                students.create(TestLibrary.unique("Петров Иван"))));
        AssignmentBatchId batch = assignments.issueToGroup(group, List.of(problem), today(), TheoryScope.NONE);
        List<ListedAssignment> issued = assignments.ofBatch(batch);
        assertThat(issued).hasSize(3);
        AssignmentId held = issued.get(1).assignment().id();
        receive(held, null);

        assertThatThrownBy(() -> assignments.deleteBatch(batch))
                .isInstanceOf(AssignmentInUseException.class)
                .hasMessageContaining("уже есть Работа")
                .hasMessageContaining("№ " + held.value());
        assertThat(assignments.ofBatch(batch)).as("ни одно Задание не удалено").hasSize(3);
    }

    /** Сценарий «Последняя Работа удалена»: «не сдано» вернулось, удаление проходит. */
    @Test
    void deletingTheLastWorkBringsBothConsequencesBack() {
        AssignmentId id = issue(today().minusDays(1));
        StudentWorkId work = receive(id, null);
        assertThat(assignments.assignment(id).notSubmitted()).isFalse();

        works.delete(owner, work);

        assertThat(assignments.assignment(id).notSubmitted()).as("Работы нет — снова не сдано").isTrue();
        assignments.delete(id);
        assertThat(assignments.list(AssignmentFilter.none())).extracting(listed -> listed.assignment().id())
                .doesNotContain(id);
    }

    private AssignmentId issue(LocalDate due) {
        return assignments.issueToStudent(student, List.of(problem), due, TheoryScope.NONE);
    }

    private StudentWorkId receive(AssignmentId assignment, Verdict verdict) {
        return works.create(owner, assignment, problem, today(), verdict, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
