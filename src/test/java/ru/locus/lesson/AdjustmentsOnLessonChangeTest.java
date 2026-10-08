package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

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
import ru.locus.lesson.MeetingAdjustment.Move;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 4.1: Поправки при правке Занятия (ADR-0048). При делении
 * Поправки с даты правки переходят к новой части; после правки у обеих
 * частей снимаются Поправки, чьих плановых дат правило не даёт.
 *
 * Занятие — по вторникам в 17:00 с 06.10.2026. Поправки заводятся
 * напрямую репозиторием: правила действий над Встречей проверяет
 * {@link MeetingActionsTest}, здесь — только их судьба при правке.
 */
class AdjustmentsOnLessonChangeTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 10, 19);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);
    private static final LocalTime SIX_PM = LocalTime.of(18, 0);

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

    private TestAccounts.Account teacher;
    private LessonId tuesdays;

    @BeforeEach
    void logInWithAWeeklyLesson() {
        teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        StudentId student = students.create(teacher.id(), TestLibrary.unique("Сидоров Илья"));
        tuesdays = lessons.create(teacher.id(), student, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Отмена переходит к новой части». */
    @Test
    void cancellationFollowsTheNewPart() {
        LocalDate cancelled = LocalDate.of(2026, 10, 27);
        cancel(tuesdays, cancelled);

        LessonId sixPm = service.change(tuesdays, new LessonChange(null, DayOfWeek.TUESDAY, SIX_PM, 60, null),
                EFFECTIVE);

        assertThat(sixPm).isNotEqualTo(tuesdays);
        assertThat(adjustments.find(teacher.id(), sixPm, cancelled)).isPresent();
        assertThat(adjustments.findByLesson(teacher.id(), tuesdays)).isEmpty();
        assertThat(meetingsOn(cancelled)).singleElement().satisfies(meeting -> {
            assertThat(meeting.start()).isEqualTo(SIX_PM);
            assertThat(meeting.cancelled()).isTrue();
        });
    }

    /** Сценарий «Отмена на дату, которой больше нет». */
    @Test
    void cancellationOfAVanishedDateIsDropped() {
        LocalDate cancelled = LocalDate.of(2026, 10, 27);
        cancel(tuesdays, cancelled);

        LessonId wednesdays = service.change(tuesdays,
                new LessonChange(null, DayOfWeek.WEDNESDAY, FIVE_PM, 60, null), EFFECTIVE);

        assertThat(adjustments.findByLesson(teacher.id(), wednesdays)).isEmpty();
        assertThat(meetingsOn(cancelled)).isEmpty();
        assertThat(meetingsOn(LocalDate.of(2026, 10, 28))).singleElement()
                .satisfies(meeting -> assertThat(meeting.cancelled()).isFalse());
    }

    /** Сценарий «Поправка до даты правки остаётся». */
    @Test
    void adjustmentBeforeTheChangeStays() {
        LocalDate planned = LocalDate.of(2026, 10, 13);
        LocalDate thursday = LocalDate.of(2026, 10, 15);
        adjustments.save(teacher.id(), new MeetingAdjustment(tuesdays, planned, false,
                new Move(thursday, FIVE_PM, 60), false));

        service.change(tuesdays, new LessonChange(null, DayOfWeek.WEDNESDAY, FIVE_PM, 60, null), EFFECTIVE);

        assertThat(adjustments.find(teacher.id(), tuesdays, planned)).isPresent();
        assertThat(meetingsOn(thursday)).singleElement()
                .satisfies(meeting -> assertThat(meeting.moved()).isTrue());
    }

    /** Последняя дата раньше Поправки — Поправка снимается, раньше — остаётся. */
    @Test
    void endingTheLessonDropsLaterAdjustments() {
        LocalDate kept = LocalDate.of(2026, 10, 13);
        LocalDate dropped = LocalDate.of(2026, 11, 3);
        cancel(tuesdays, kept);
        cancel(tuesdays, dropped);

        service.change(tuesdays, new LessonChange(null, DayOfWeek.TUESDAY, FIVE_PM, 60,
                LocalDate.of(2026, 10, 27)), LocalDate.of(2026, 10, 8));

        assertThat(adjustments.findByLesson(teacher.id(), tuesdays))
                .extracting(MeetingAdjustment::plannedDate).containsExactly(kept);
    }

    /** Разовое Занятие на новую дату теряет Поправку старой даты. */
    @Test
    void oneOffLessonOnANewDateLosesItsAdjustment() {
        LessonId once = lessons.create(teacher.id(), students.create(teacher.id(),
                TestLibrary.unique("Орлова Анна")), LessonTiming.once(TUESDAY, FIVE_PM, 60));
        cancel(once, TUESDAY);

        service.change(once, new LessonChange(LocalDate.of(2026, 10, 9), null, FIVE_PM, 60, null), null);

        assertThat(adjustments.findByLesson(teacher.id(), once)).isEmpty();
    }

    private void cancel(LessonId lesson, LocalDate plannedDate) {
        adjustments.save(teacher.id(), new MeetingAdjustment(lesson, plannedDate, true, null, false));
    }

    private List<Meeting> meetingsOn(LocalDate date) {
        return service.week(date).days().stream()
                .filter(day -> day.date().equals(date))
                .flatMap(day -> day.meetings().stream())
                .toList();
    }
}
