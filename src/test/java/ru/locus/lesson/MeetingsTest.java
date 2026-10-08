package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.student.GroupId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Задача 2.1: Встречи выводятся из правила чистой функцией (ADR-0047) —
 * модульно, без базы, на краях отрезка и правила.
 */
class MeetingsTest {

    private static final UserId OWNER = new UserId(1);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    private long nextId = 1;

    /** Сценарий «Разовое Занятие с Группой»: Встреча только в свою дату. */
    @Test
    void onceLessonGivesOneMeetingOnlyWithinTheRange() {
        ListedLesson once = withGroup(LessonTiming.once(LocalDate.of(2026, 10, 10), LocalTime.of(12, 0), 90));

        List<Meeting> inside = Meetings.between(List.of(once), MONDAY, SUNDAY);
        List<Meeting> before = Meetings.between(List.of(once), MONDAY.minusWeeks(1), SUNDAY.minusWeeks(1));
        List<Meeting> after = Meetings.between(List.of(once), MONDAY.plusWeeks(1), SUNDAY.plusWeeks(1));

        assertThat(inside).singleElement().satisfies(meeting -> {
            assertThat(meeting.date()).isEqualTo(LocalDate.of(2026, 10, 10));
            assertThat(meeting.start()).isEqualTo(LocalTime.of(12, 0));
            assertThat(meeting.end()).isEqualTo(LocalTime.of(13, 30));
            assertThat(meeting.addresseeName()).isEqualTo("9Б");
            assertThat(meeting.lesson()).isEqualTo(once.lesson().id());
        });
        assertThat(before).isEmpty();
        assertThat(after).isEmpty();
    }

    /** Разовое на самих краях отрезка попадает в него. */
    @Test
    void onceLessonOnTheEdgesOfTheRangeIsIncluded() {
        ListedLesson onMonday = withStudent(LessonTiming.once(MONDAY, FIVE_PM, 60), false);
        ListedLesson onSunday = withStudent(LessonTiming.once(SUNDAY, FIVE_PM, 60), false);

        assertThat(Meetings.between(List.of(onMonday, onSunday), MONDAY, SUNDAY))
                .extracting(Meeting::date)
                .containsExactly(MONDAY, SUNDAY);
    }

    /** Сценарий «Неделя до первой встречи»: Встреч нет. */
    @Test
    void weeklyLessonGivesNothingBeforeItsFirstDate() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY.plusWeeks(1), null, FIVE_PM, 60), false);

        assertThat(Meetings.between(List.of(weekly), MONDAY, SUNDAY)).isEmpty();
    }

    /** Первая встреча — в своей неделе, в свой день. */
    @Test
    void weeklyLessonMeetsOnItsFirstDate() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);

        assertThat(Meetings.between(List.of(weekly), MONDAY, SUNDAY))
                .extracting(Meeting::date)
                .containsExactly(TUESDAY);
    }

    /** Сценарий «Последняя встреча еженедельного Занятия»: 20.10 есть, 27.10 нет. */
    @Test
    void weeklyLessonMeetsOnItsLastDateAndNotAfter() {
        LocalDate last = LocalDate.of(2026, 10, 20);
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, last, FIVE_PM, 60), false);

        assertThat(Meetings.between(List.of(weekly), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)))
                .extracting(Meeting::date)
                .containsExactly(last);
        assertThat(Meetings.between(List.of(weekly), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)))
                .isEmpty();
        assertThat(Meetings.between(List.of(weekly), MONDAY, LocalDate.of(2026, 11, 1)))
                .extracting(Meeting::date)
                .as("за четыре недели — ровно три Встречи: 06, 13 и 20 октября")
                .containsExactly(TUESDAY, TUESDAY.plusWeeks(1), last);
    }

    /** Бессрочное Занятие идёт и через год. */
    @Test
    void weeklyLessonWithoutLastDateGoesOnForever() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);

        assertThat(Meetings.between(List.of(weekly), MONDAY.plusYears(1), SUNDAY.plusYears(1)))
                .extracting(Meeting::date)
                .containsExactly(LocalDate.of(2027, 10, 5));
    }

    /** Сценарий «Две Встречи в одно время»: обе показаны, пересечения не проверяются. */
    @Test
    void twoMeetingsAtTheSameTimeAreBothShown() {
        ListedLesson first = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        ListedLesson second = withGroup(LessonTiming.once(TUESDAY, FIVE_PM, 45));

        assertThat(Meetings.between(List.of(first, second), MONDAY, SUNDAY))
                .extracting(Meeting::lesson)
                .containsExactly(first.lesson().id(), second.lesson().id());
    }

    /**
     * Неделя перехода на зимнее время (в Европе — 25.10.2026): дата и время
     * местные, Встреча остаётся в 17:00 по воскресеньям до и после перехода.
     */
    @Test
    void winterTimeTransitionDoesNotMoveMeetings() {
        LocalDate sundayBefore = LocalDate.of(2026, 10, 18);
        ListedLesson weekly = withStudent(LessonTiming.weekly(sundayBefore, null, FIVE_PM, 60), false);

        assertThat(Meetings.between(List.of(weekly), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 1)))
                .allSatisfy(meeting -> {
                    assertThat(meeting.start()).isEqualTo(FIVE_PM);
                    assertThat(meeting.end()).isEqualTo(LocalTime.of(18, 0));
                })
                .extracting(Meeting::date)
                .containsExactly(sundayBefore, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 1));
    }

    /** Порядок — по дате, затем по времени, а не по порядку Занятий на входе. */
    @Test
    void meetingsAreOrderedByDateAndTime() {
        ListedLesson lateTuesday = withStudent(LessonTiming.weekly(TUESDAY, null, LocalTime.of(19, 0), 60), false);
        ListedLesson earlyTuesday = withStudent(LessonTiming.weekly(TUESDAY, null, LocalTime.of(9, 0), 60), false);
        ListedLesson monday = withGroup(LessonTiming.once(MONDAY, LocalTime.of(20, 0), 60));

        assertThat(Meetings.between(List.of(lateTuesday, earlyTuesday, monday), MONDAY, SUNDAY))
                .extracting(Meeting::lesson)
                .containsExactly(monday.lesson().id(), earlyTuesday.lesson().id(), lateTuesday.lesson().id());
    }

    /** Признак выбытия переходит на каждую Встречу. */
    @Test
    void withdrawnStudentMarksEveryMeeting() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), true);

        assertThat(Meetings.between(List.of(weekly), MONDAY, SUNDAY.plusWeeks(1)))
                .hasSize(2)
                .allMatch(Meeting::withdrawn);
    }

    private ListedLesson withStudent(LessonTiming timing, boolean withdrawn) {
        Lesson lesson = new Lesson(new LessonId(nextId++), OWNER, new StudentId(1), null, timing);
        return new ListedLesson(lesson, "Иванов Пётр", withdrawn);
    }

    private ListedLesson withGroup(LessonTiming timing) {
        Lesson lesson = new Lesson(new LessonId(nextId++), OWNER, null, new GroupId(1), timing);
        return new ListedLesson(lesson, "9Б", false);
    }
}
