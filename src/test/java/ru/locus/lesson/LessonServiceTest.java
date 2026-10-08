package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.student.GroupId;
import ru.locus.student.GroupNotFoundException;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentNotFoundException;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задачи 3.1 и 3.2: заведение, правка с даты и удаление Занятия.
 *
 * Правила проверяются на сервисе, вошедший — настоящая учётная запись:
 * владельца сервис берёт у неё. Чужое заводится напрямую через репозитории
 * от имени второго Учителя. Встречи после правки читаются тем же
 * {@link LessonService#week}, что показывает экран.
 */
class LessonServiceTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    @Autowired
    private LessonService service;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account alice;
    private TestAccounts.Account bob;
    private StudentId student;

    @BeforeEach
    void logInAsATeacher() {
        alice = accounts.settled(Role.TEACHER);
        bob = accounts.settled(Role.TEACHER);
        LoggedIn.as(alice);
        student = students.create(alice.id(), TestLibrary.unique("Иванов Пётр"));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Еженедельное Занятие с Учеником». */
    @Test
    void weeklyLessonWithAStudentIsCreated() {
        LessonId id = service.create(student, null, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));

        ListedLesson listed = service.lesson(id);
        assertThat(listed.lesson().student()).isEqualTo(student);
        assertThat(listed.lesson().timing().weekly()).isTrue();
        assertThat(startsOn(LocalDate.of(2026, 10, 13))).containsExactly(FIVE_PM);
        assertThat(startsOn(LocalDate.of(2026, 12, 29))).containsExactly(FIVE_PM);
    }

    /** Сценарий «Разовое Занятие с Группой». */
    @Test
    void oneOffLessonWithAGroupIsCreated() {
        GroupId group = groups.create(alice.id(), TestLibrary.unique("9А"));
        LocalDate saturday = LocalDate.of(2026, 10, 10);

        service.create(null, group, LessonTiming.once(saturday, LocalTime.NOON, 90));

        assertThat(startsOn(saturday)).containsExactly(LocalTime.NOON);
        assertThat(startsOn(saturday.plusWeeks(1))).isEmpty();
    }

    /** Сценарий «Без адресата или с двумя адресатами». */
    @Test
    void addresseeIsExactlyOne() {
        GroupId group = groups.create(alice.id(), TestLibrary.unique("9А"));
        LessonTiming timing = LessonTiming.once(TUESDAY, FIVE_PM, 60);

        assertThatThrownBy(() -> service.create(null, null, timing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ровно один");
        assertThatThrownBy(() -> service.create(student, group, timing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ровно один");
        assertThat(lessons.countByStudent(alice.id(), student)).isZero();
    }

    /** Сценарии «Последняя дата раньше первой» и «Недопустимая длительность»: отказ до записи. */
    @Test
    void timingIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.create(student, null,
                LessonTiming.of(TUESDAY, TUESDAY.minusDays(1), true, FIVE_PM, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("раньше первой");
        assertThatThrownBy(() -> service.create(student, null, LessonTiming.of(TUESDAY, null, true, FIVE_PM, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Длительность");
        assertThatThrownBy(() -> service.create(student, null, LessonTiming.of(TUESDAY, null, true, FIVE_PM, 721)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Длительность");
        assertThatThrownBy(() -> service.create(student, null, LessonTiming.of(TUESDAY, null, true, FIVE_PM, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Длительность");
        assertThatThrownBy(() -> service.create(student, null,
                LessonTiming.of(TUESDAY, TUESDAY.plusWeeks(2), false, FIVE_PM, 60)))
                .as("последняя дата только у еженедельного")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lessons.countByStudent(alice.id(), student)).isZero();
    }

    /** Сценарий «Занятие выбывшему». */
    @Test
    void withdrawnStudentIsRefused() {
        students.setWithdrawn(alice.id(), student, true);

        assertThatThrownBy(() -> service.create(student, null, LessonTiming.once(TUESDAY, FIVE_PM, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("выбыл");
        assertThat(lessons.countByStudent(alice.id(), student)).isZero();
    }

    /** Сценарий «Чужой Ученик адресатом»; чужая Группа — так же. */
    @Test
    void foreignAddresseeIsUnknown() {
        StudentId foreignStudent = students.create(bob.id(), TestLibrary.unique("Чужой"));
        GroupId foreignGroup = groups.create(bob.id(), TestLibrary.unique("Чужая"));
        LessonTiming timing = LessonTiming.once(TUESDAY, FIVE_PM, 60);

        assertThatThrownBy(() -> service.create(foreignStudent, null, timing))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.create(null, foreignGroup, timing))
                .isInstanceOf(GroupNotFoundException.class);
    }

    /** Сценарий «Перевод со вторника на среду»: прошлое остаётся, Занятие делится. */
    @Test
    void movingFromTuesdayToWednesdaySplitsTheLesson() {
        LessonId tuesdays = service.create(student, null, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LocalTime sevenPm = LocalTime.of(19, 0);

        LessonId wednesdays = service.change(tuesdays,
                new LessonChange(null, DayOfWeek.WEDNESDAY, sevenPm, 60, null), LocalDate.of(2026, 10, 19));

        assertThat(wednesdays).isNotEqualTo(tuesdays);
        assertThat(startsOn(TUESDAY)).containsExactly(FIVE_PM);
        assertThat(startsOn(LocalDate.of(2026, 10, 13))).containsExactly(FIVE_PM);
        assertThat(startsOn(LocalDate.of(2026, 10, 20))).as("вторник после перевода").isEmpty();
        assertThat(startsOn(LocalDate.of(2026, 10, 21))).containsExactly(sevenPm);
        assertThat(startsOn(LocalDate.of(2026, 10, 28))).containsExactly(sevenPm);
        assertThat(service.lesson(tuesdays).lesson().timing().lastDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(service.lesson(wednesdays).lesson().student()).isEqualTo(student);
    }

    /** Сценарий «Правка до первой встречи»: без деления. */
    @Test
    void changeNotLaterThanTheFirstMeetingIsInPlace() {
        LessonId id = service.create(student, null, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LocalTime sixPm = LocalTime.of(18, 0);

        LessonId after = service.change(id, new LessonChange(null, DayOfWeek.TUESDAY, sixPm, 45, null), TUESDAY);

        assertThat(after).isEqualTo(id);
        assertThat(lessons.countByStudent(alice.id(), student)).isEqualTo(1);
        assertThat(startsOn(TUESDAY)).containsExactly(sixPm);
    }

    /** Сценарий «Окончание еженедельного Занятия»: только последняя дата — без деления. */
    @Test
    void endingAWeeklyLessonIsInPlace() {
        LessonId id = service.create(student, null, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LocalDate last = LocalDate.of(2026, 10, 20);

        LessonId after = service.change(id, new LessonChange(null, DayOfWeek.TUESDAY, FIVE_PM, 60, last),
                LocalDate.of(2026, 10, 15));

        assertThat(after).isEqualTo(id);
        assertThat(lessons.countByStudent(alice.id(), student)).isEqualTo(1);
        assertThat(startsOn(TUESDAY)).containsExactly(FIVE_PM);
        assertThat(startsOn(last)).containsExactly(FIVE_PM);
        assertThat(startsOn(LocalDate.of(2026, 10, 27))).isEmpty();
    }

    /** Разовое Занятие правится на месте, дата «с которой» не читается. */
    @Test
    void oneOffLessonIsChangedInPlace() {
        LessonId id = service.create(student, null, LessonTiming.once(TUESDAY, FIVE_PM, 60));
        LocalDate friday = LocalDate.of(2026, 10, 9);

        LessonId after = service.change(id, new LessonChange(friday, null, FIVE_PM, 60, null), null);

        assertThat(after).isEqualTo(id);
        assertThat(startsOn(TUESDAY)).isEmpty();
        assertThat(startsOn(friday)).containsExactly(FIVE_PM);
    }

    /** Сценарий «Удаление Занятия»: Встреч нет ни в одной неделе, Ученик остался. */
    @Test
    void lessonIsDeletedAtAnyTime() {
        LessonId id = service.create(student, null, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));

        service.delete(id);

        assertThat(startsOn(TUESDAY)).isEmpty();
        assertThat(startsOn(LocalDate.of(2026, 11, 3))).isEmpty();
        assertThat(students.findById(alice.id(), student)).isPresent();
    }

    /** Сценарий «Правка чужого Занятия»: чужое неотличимо от несуществующего и не меняется. */
    @Test
    void foreignLessonIsNotFound() {
        StudentId foreignStudent = students.create(bob.id(), TestLibrary.unique("Чужой"));
        LessonId foreign = lessons.create(bob.id(), foreignStudent, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LessonChange change = new LessonChange(null, DayOfWeek.WEDNESDAY, FIVE_PM, 60, null);

        assertThatThrownBy(() -> service.lesson(foreign)).isInstanceOf(LessonNotFoundException.class);
        assertThatThrownBy(() -> service.change(foreign, change, LocalDate.of(2026, 10, 19)))
                .isInstanceOf(LessonNotFoundException.class);
        assertThatThrownBy(() -> service.delete(foreign)).isInstanceOf(LessonNotFoundException.class);
        assertThat(lessons.findById(bob.id(), foreign)).get()
                .extracting(found -> found.lesson().timing())
                .isEqualTo(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
    }

    private List<LocalTime> startsOn(LocalDate date) {
        return service.week(date).days().stream()
                .filter(day -> day.date().equals(date))
                .flatMap(day -> day.meetings().stream())
                .map(Meeting::start)
                .toList();
    }
}
