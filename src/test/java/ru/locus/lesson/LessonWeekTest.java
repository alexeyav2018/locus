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
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 2.2: неделя и сегодняшний день расписания считаются от часов
 * приложения ({@link TestClock}), а не от системной даты, и неделя идёт
 * с понедельника по воскресенье.
 */
class LessonWeekTest extends IntegrationTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);

    @Autowired
    private LessonService service;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private MeetingAdjustmentRepository adjustments;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestClock clock;

    private TestAccounts.Account teacher;
    private StudentId student;

    @BeforeEach
    void logInAsATeacherOnThursday() {
        teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        student = students.create(teacher.id(), TestLibrary.unique("Иванов Пётр"));
        setToday(THURSDAY);
    }

    @AfterEach
    void logOutAndResetTheClock() {
        clock.reset();
        LoggedIn.nobody();
    }

    /** Сценарии «Текущая неделя по умолчанию» и «Любая дата открывает свою неделю». */
    @Test
    void withoutDateTheCurrentWeekIsShownFromMondayToSunday() {
        Week week = service.week(null);

        assertThat(week.monday()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(week.sunday()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(week.current()).isTrue();
        assertThat(week.days()).hasSize(7);
        assertThat(week.days()).filteredOn(Week.Day::today)
                .singleElement()
                .extracting(Week.Day::date)
                .isEqualTo(THURSDAY);
    }

    /** Любая дата открывает свою неделю; чужая неделя — не текущая и без выделенного дня. */
    @Test
    void anyDateOpensItsWeek() {
        Week week = service.week(LocalDate.of(2026, 10, 15));

        assertThat(week.monday()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(week.previous()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(week.next()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(week.current()).isFalse();
        assertThat(week.days()).noneMatch(Week.Day::today);
    }

    /** Встречи раскладываются по своим дням, пустой день остаётся пустым. */
    @Test
    void meetingsLandOnTheirDays() {
        LocalDate tuesday = LocalDate.of(2026, 10, 6);
        lessons.create(teacher.id(), student, LessonTiming.weekly(tuesday, null, LocalTime.of(17, 0), 60));
        lessons.create(teacher.id(), student, LessonTiming.once(THURSDAY, LocalTime.of(15, 0), 60));

        Week week = service.week(null);

        assertThat(week.days().get(1).meetings()).extracting(Meeting::date).containsExactly(tuesday);
        assertThat(week.days().get(2).meetings()).as("среда пустая").isEmpty();
        assertThat(service.today().meetings()).extracting(Meeting::start).containsExactly(LocalTime.of(15, 0));
    }

    /**
     * Встреча, перенесённая из прошлой недели, видна в этой и сегодня, хотя
     * правило разового Занятия в эту неделю Встречи не даёт; на её плановом
     * дне в прошлой неделе — строка «перенесена на» (ADR-0048).
     */
    @Test
    void meetingMovedFromThePreviousWeekIsShownInThisOne() {
        LocalDate lastTuesday = LocalDate.of(2026, 9, 29);
        LessonId once = lessons.create(teacher.id(), student, LessonTiming.once(lastTuesday, LocalTime.of(17, 0), 60));
        adjustments.save(teacher.id(), MeetingAdjustment.none(once, lastTuesday)
                .withMove(new MeetingAdjustment.Move(THURSDAY, LocalTime.of(18, 0), 45)));

        Week week = service.week(null);

        assertThat(week.days().get(3).meetings()).singleElement().satisfies(meeting -> {
            assertThat(meeting.lesson()).isEqualTo(once);
            assertThat(meeting.plannedDate()).isEqualTo(lastTuesday);
            assertThat(meeting.start()).isEqualTo(LocalTime.of(18, 0));
            assertThat(meeting.moved()).isTrue();
        });
        assertThat(service.today().meetings()).extracting(Meeting::lesson).containsExactly(once);
        assertThat(service.week(lastTuesday).days().get(1)).satisfies(day -> {
            assertThat(day.meetings()).isEmpty();
            assertThat(day.movedAway()).extracting(MovedAway::date).containsExactly(THURSDAY);
        });
    }

    /** Сценарий «Администратор без роли Учителя»: расписания у него нет. */
    @Test
    void administratorWithoutTeacherRoleIsRefused() {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.week(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.today()).isInstanceOf(AccessDeniedException.class);
    }

    private void setToday(LocalDate date) {
        Instant noon = LocalDateTime.of(date, LocalTime.NOON).atZone(clock.getZone()).toInstant();
        clock.shift(Duration.between(Instant.now(), noon));
    }
}
