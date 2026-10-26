package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.warmup.WarmupOutcome.SUCCEEDED;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.Addressee;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryRepository;
import ru.locus.mastery.MasteryService;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupNotFoundException;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentNotFoundException;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Задача 3.5: Учитель видит и ведёт только свои Разминки — сценарии
 * требования «Учитель видит и ведёт только свои Разминки» спеки
 * {@code warmups} (ADR-0027, ADR-0005).
 *
 * Чужая Разминка неотличима от несуществующей на каждой операции сервиса —
 * показ, итоги, ячейки, отметки, удаление — и после попытки не меняется.
 * Чужой Ученик и чужая Группа как адресат — несуществующие. Списки двух
 * Учителей независимы. Пользователю без роли Учителя Разминки недоступны
 * вовсе. Чужие данные заводятся репозиториями от имени Учителя А,
 * а действует сервис от имени вошедшего Учителя Б.
 */
class WarmupsAreFilteredByOwnerTest extends IntegrationTest {

    private static final LocalDate HELD = LocalDate.of(2026, 10, 1);

    @Autowired
    private WarmupService service;

    @Autowired
    private MasteryService mastery;

    @Autowired
    private WarmupRepository warmups;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Чужая Разминка»: каждая операция — «не найдено», Разминка не меняется. */
    @Test
    void anotherTeachersWarmupIsIndistinguishableFromMissing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        ProblemId problem = library.problem(topic, method);
        StudentId student = students.create(first.id(), TestLibrary.unique("Иванов Пётр"));
        WarmupId foreign = warmups.create(first.id(), HELD, null, List.of(student), List.of(problem));
        WarmupId missing = new WarmupId(foreign.value() + 1_000_000);
        LoggedIn.as(accounts.settled(Role.TEACHER));

        for (WarmupId id : List.of(foreign, missing)) {
            assertThatThrownBy(() -> service.warmup(id)).isInstanceOf(WarmupNotFoundException.class);
            assertThatThrownBy(() -> service.record(id, student, List.of(new Outcome(problem, SUCCEEDED)),
                    List.of(new Mark(topic, method, MASTERED))))
                    .isInstanceOf(WarmupNotFoundException.class);
            assertThatThrownBy(() -> mastery.cellsOfWarmup(id)).isInstanceOf(WarmupNotFoundException.class);
            assertThatThrownBy(() -> mastery.markByWarmup(id, student, List.of(new Mark(topic, method, MASTERED))))
                    .isInstanceOf(WarmupNotFoundException.class);
            assertThatThrownBy(() -> service.delete(id)).isInstanceOf(WarmupNotFoundException.class);
        }

        assertThat(warmups.findById(first.id(), foreign)).as("Разминка Учителя А на месте").isPresent();
        assertThat(warmups.findOutcomes(first.id(), foreign)).as("итогов не прибавилось").isEmpty();
        assertThat(marks.findByStudent(first.id(), student)).as("отметок не прибавилось").isEmpty();
    }

    /** Сценарий «Чужой адресат»: чужой Ученик и чужая Группа — как несуществующие. */
    @Test
    void anotherTeachersAddresseeIsIndistinguishableFromMissing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        ProblemId problem = library.problem(library.topic());
        StudentId student = students.create(first.id(), TestLibrary.unique("Иванов Пётр"));
        GroupId group = groups.create(first.id(), TestLibrary.unique("9Б"));
        groups.setMembers(first.id(), group, List.of(student));
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.hold(new Addressee.ToStudent(student), HELD, List.of(problem)))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.hold(new Addressee.ToGroup(group), HELD, List.of(problem)))
                .isInstanceOf(GroupNotFoundException.class);
        assertThatThrownBy(() -> service.list(student))
                .as("Разминки чужого Ученика — не пустой список, а «не найдено»")
                .isInstanceOf(StudentNotFoundException.class);
        assertThat(warmups.find(first.id(), null)).as("у Учителя А Разминок не появилось").isEmpty();
    }

    /** Сценарий «Списки двух Учителей независимы». */
    @Test
    void twoTeachersSeeOnlyTheirOwnWarmups() {
        ProblemId problem = library.problem(library.topic());
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        WarmupId ofFirst = holdAs(first, problem);
        WarmupId ofSecond = holdAs(second, problem);

        LoggedIn.as(first);
        assertThat(service.list(null)).extracting(row -> row.warmup().id()).containsExactly(ofFirst);
        LoggedIn.as(second);
        assertThat(service.list(null)).extracting(row -> row.warmup().id()).containsExactly(ofSecond);
    }

    /** Сценарий «Администратор без роли Учителя». */
    @Test
    void administratorWithoutTeacherRoleIsDenied() {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.list(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.warmup(new WarmupId(1))).isInstanceOf(AccessDeniedException.class);
    }

    private WarmupId holdAs(TestAccounts.Account teacher, ProblemId problem) {
        LoggedIn.as(teacher);
        StudentId student = students.create(teacher.id(), TestLibrary.unique("Иванов Пётр"));
        return service.hold(new Addressee.ToStudent(student), HELD, List.of(problem));
    }
}
