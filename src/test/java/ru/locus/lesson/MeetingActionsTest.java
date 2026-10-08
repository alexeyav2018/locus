package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.lesson.MeetingAdjustment.Move;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 3.1: действия над одной Встречей — перенос, отмена, возврат
 * и неявка (ADR-0048) — и их отказы. Сегодня — вторник 13.10.2026
 * на {@link TestClock}; Занятие по вторникам в 17:00 с 06.10.2026.
 */
class MeetingActionsTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final LocalDate NEXT_TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    @Autowired
    private LessonService service;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private MeetingAdjustmentRepository adjustments;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestClock clock;

    private TestAccounts.Account teacher;
    private LessonId lesson;

    @BeforeEach
    void logInWithAWeeklyLesson() {
        teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        StudentId student = students.create(teacher.id(), TestLibrary.unique("Петров Иван"));
        lesson = lessons.create(teacher.id(), student,
                LessonTiming.weekly(LocalDate.of(2026, 10, 6), null, FIVE_PM, 60));
        Instant noon = LocalDateTime.of(TODAY, LocalTime.NOON).atZone(clock.getZone()).toInstant();
        clock.shift(Duration.between(Instant.now(), noon));
    }

    @AfterEach
    void logOutAndResetTheClock() {
        clock.reset();
        LoggedIn.nobody();
    }

    /** Сценарии «Отмена одной Встречи» и «Вернуть отменённую Встречу». */
    @Test
    void cancelledMeetingStaysOnItsDateAndIsRestored() {
        service.cancel(lesson, NEXT_TUESDAY);

        assertThat(service.meeting(lesson, NEXT_TUESDAY).meeting().cancelled()).isTrue();
        assertThat(service.meeting(lesson, LocalDate.of(2026, 10, 27)).meeting().cancelled()).isFalse();

        service.restore(lesson, NEXT_TUESDAY);

        assertThat(service.meeting(lesson, NEXT_TUESDAY).meeting().cancelled()).isFalse();
        assertThat(adjustments.findByLesson(teacher.id(), lesson)).as("пустая Поправка не хранится").isEmpty();
    }

    /** Перенос снимает отмену, повторный перенос меняет место той же Встречи, возврат — на плановое. */
    @Test
    void moveReplacesCancellationAndIsRestored() {
        service.cancel(lesson, NEXT_TUESDAY);
        service.move(lesson, NEXT_TUESDAY, new Move(LocalDate.of(2026, 10, 22), LocalTime.of(18, 0), 60));
        service.move(lesson, NEXT_TUESDAY, new Move(LocalDate.of(2026, 11, 2), LocalTime.of(19, 0), 45));

        Meeting moved = service.meeting(lesson, NEXT_TUESDAY).meeting();
        assertThat(moved.cancelled()).isFalse();
        assertThat(moved.moved()).isTrue();
        assertThat(moved.date()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(moved.end()).isEqualTo(LocalTime.of(19, 45));

        service.restore(lesson, NEXT_TUESDAY);

        Meeting restored = service.meeting(lesson, NEXT_TUESDAY).meeting();
        assertThat(restored.date()).isEqualTo(NEXT_TUESDAY);
        assertThat(restored.start()).isEqualTo(FIVE_PM);
        assertThat(service.week(LocalDate.of(2026, 11, 2)).days().get(0).meetings()).isEmpty();
    }

    /** Сценарий «Перенос с недопустимой длительностью»: Встреча остаётся на месте. */
    @Test
    void moveWithZeroDurationIsRefused() {
        assertThatThrownBy(() -> service.move(lesson, NEXT_TUESDAY,
                Move.of(LocalDate.of(2026, 10, 22), FIVE_PM, 0)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.meeting(lesson, NEXT_TUESDAY).meeting().moved()).isFalse();
    }

    /** Сценарии «Ученик не пришёл» и «Снять неявку». */
    @Test
    void absenceIsMarkedOnTodaysMeetingAndRemoved() {
        service.markAbsence(lesson, TODAY, true);

        assertThat(service.meeting(lesson, TODAY).meeting().absent()).isTrue();

        service.markAbsence(lesson, TODAY, false);

        assertThat(service.meeting(lesson, TODAY).meeting().absent()).isFalse();
        assertThat(adjustments.findByLesson(teacher.id(), lesson)).isEmpty();
    }

    /** Сценарий «Неявка в будущем». */
    @Test
    void absenceInTheFutureIsRefused() {
        assertThat(service.meeting(lesson, NEXT_TUESDAY).started()).isFalse();

        assertThatThrownBy(() -> service.markAbsence(lesson, NEXT_TUESDAY, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ещё не наступила");
        assertThat(adjustments.findByLesson(teacher.id(), lesson)).isEmpty();
    }

    /** Встреча с неявкой не уезжает в будущее ни переносом, ни возвратом. */
    @Test
    void meetingWithAbsenceIsNotMovedIntoTheFuture() {
        service.markAbsence(lesson, TODAY, true);

        assertThatThrownBy(() -> service.move(lesson, TODAY, new Move(NEXT_TUESDAY.plusDays(1), FIVE_PM, 60)))
                .isInstanceOf(IllegalArgumentException.class);

        Meeting meeting = service.meeting(lesson, TODAY).meeting();
        assertThat(meeting.moved()).isFalse();
        assertThat(meeting.absent()).isTrue();
    }

    /** Сценарий «Неявка на Встрече Группы». */
    @Test
    void absenceOnAGroupMeetingIsRefused() {
        GroupId group = groups.create(teacher.id(), TestLibrary.unique("9Б"));
        LessonId groupLesson = lessons.create(teacher.id(), group, LessonTiming.once(TODAY, FIVE_PM, 60));

        assertThatThrownBy(() -> service.markAbsence(groupLesson, TODAY, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Группой");
        assertThat(service.meeting(groupLesson, TODAY).meeting().absent()).isFalse();
    }

    /** У отменённой Встречи неявки не бывает. */
    @Test
    void absenceOnACancelledMeetingIsRefused() {
        service.cancel(lesson, TODAY);

        assertThatThrownBy(() -> service.markAbsence(lesson, TODAY, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("отменена");
    }

    /** Сценарий «Отмена Встречи с неявкой»: сначала снять неявку. */
    @Test
    void cancellingAMeetingWithAbsenceIsRefused() {
        service.markAbsence(lesson, TODAY, true);

        assertThatThrownBy(() -> service.cancel(lesson, TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пришёл");
        assertThat(service.meeting(lesson, TODAY).meeting().cancelled()).isFalse();
    }

    /** Сценарий «Дата без Встречи»: среда у Занятия по вторникам — как несуществующее. */
    @Test
    void dateWithoutAMeetingIsNotFound() {
        LocalDate wednesday = LocalDate.of(2026, 10, 14);

        assertThatThrownBy(() -> service.meeting(lesson, wednesday)).isInstanceOf(MeetingNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(lesson, wednesday)).isInstanceOf(MeetingNotFoundException.class);
        assertThatThrownBy(() -> service.meeting(lesson, LocalDate.of(2026, 9, 29)))
                .as("до первой встречи")
                .isInstanceOf(MeetingNotFoundException.class);
    }
}
