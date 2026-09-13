package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.user.Role;

/**
 * Задача 5.3: «не сдано» вычисляется из срока и часов, а не хранится
 * (инвариант 12 domain-model.md, ADR-0016) — признак готовности карточки:
 * сдвиг системного времени меняет состояние без единого действия
 * над Заданием.
 *
 * Часы — {@link TestClock}, подменяющий бин {@link Clock} на весь контекст;
 * сдвиг снимается после каждого теста, иначе соседние Задания тихо
 * станут несданными. «Сегодня» здесь берётся у тех же часов, что
 * и у сервиса: день срока — ещё не просрочка, и граница суток должна
 * проходить для обоих в одном месте.
 *
 * Вторая половина условия — «Работы нет» — сегодня выполняется тождественно:
 * ответчиков на {@link AssignmentWork} не существует, и проверить её
 * обязана работа {@code submission-review}.
 */
class NotSubmittedTest extends IntegrationTest {

    @Autowired
    private AssignmentService service;

    @Autowired
    private StudentService students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TestClock clock;

    private StudentId student;
    private ProblemId problem;

    @BeforeEach
    void logInAsATeacher() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        student = students.create(TestLibrary.unique("Иванов Пётр"));
        problem = library.problem(library.topic());
    }

    @AfterEach
    void logOutAndResetTheClock() {
        clock.reset();
        LoggedIn.nobody();
    }

    /** Сценарий «Срок не прошёл»: день срока — ещё не просрочка. */
    @Test
    void dueTodayIsNotYetNotSubmitted() {
        AssignmentId id = issue(today());

        assertThat(service.assignment(id).notSubmitted()).isFalse();
    }

    /** Сценарий «Срок прошёл, Работы нет». */
    @Test
    void dueYesterdayIsNotSubmitted() {
        AssignmentId id = issue(today().minusDays(1));

        assertThat(service.assignment(id).notSubmitted()).isTrue();
    }

    /** Сценарий «Сдвиг часов»: состояние меняется без действий над Заданием. */
    @Test
    void shiftingTheClockChangesTheStateWithoutTouchingTheAssignment() {
        AssignmentId id = issue(today().plusDays(2));
        assertThat(service.assignment(id).notSubmitted()).isFalse();

        clock.shift(Duration.ofDays(3));
        assertThat(service.assignment(id).notSubmitted()).as("через три дня срок прошёл").isTrue();

        clock.reset();
        assertThat(service.assignment(id).notSubmitted()).as("часы вернулись — снова не просрочено").isFalse();
    }

    /** Сценарий «Перенос срока»: несданное после переноса на неделю вперёд — уже нет. */
    @Test
    void movingTheDueDateForwardClearsNotSubmitted() {
        AssignmentId id = issue(today().minusDays(1));
        assertThat(service.assignment(id).notSubmitted()).isTrue();

        service.changeDueDate(id, today().plusWeeks(1));

        assertThat(service.assignment(id).notSubmitted()).isFalse();
    }

    /** Сценарий «Только несданные за период»: позавчера, вчера, завтра; период от позавчера до сегодня. */
    @Test
    void onlyNotSubmittedWithinAPeriod() {
        LocalDate today = today();
        AssignmentId dayBeforeYesterday = issue(today.minusDays(2));
        AssignmentId yesterday = issue(today.minusDays(1));
        AssignmentId tomorrow = issue(today.plusDays(1));

        List<ListedAssignment> shown = service.list(
                new AssignmentFilter(null, null, today.minusDays(2), today, true));

        assertThat(shown).extracting(listed -> listed.assignment().id())
                .containsExactly(dayBeforeYesterday, yesterday)
                .doesNotContain(tomorrow);
        assertThat(shown).allMatch(ListedAssignment::notSubmitted);

        List<ListedAssignment> all = service.list(new AssignmentFilter(null, null, null, null, true));
        assertThat(all).extracting(listed -> listed.assignment().id())
                .as("без периода — все несданные, без завтрашнего")
                .containsExactly(dayBeforeYesterday, yesterday);
        assertThat(service.list(AssignmentFilter.none())).hasSize(3);
    }

    private AssignmentId issue(LocalDate due) {
        return service.issueToStudent(student, List.of(problem), due, TheoryScope.NONE);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
