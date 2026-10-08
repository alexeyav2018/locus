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
 * Встречи выводятся из правила и Поправок чистой функцией (ADR-0047,
 * ADR-0048) — модульно, без базы, на краях отрезка и правила.
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

    /** Сценарий «Отмена одной Встречи»: отменённая остаётся на своей дате с пометкой, соседние — как обычно. */
    @Test
    void cancelledMeetingStaysOnItsPlannedDate() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        LocalDate cancelled = TUESDAY.plusWeeks(1);
        MeetingAdjustment cancellation = MeetingAdjustment.none(weekly.lesson().id(), cancelled).withCancelled(true);

        List<Meeting> meetings = Meetings.between(List.of(weekly), List.of(cancellation), MONDAY,
                SUNDAY.plusWeeks(2));

        assertThat(meetings).extracting(Meeting::date)
                .containsExactly(TUESDAY, cancelled, TUESDAY.plusWeeks(2));
        assertThat(meetings).extracting(Meeting::cancelled).containsExactly(false, true, false);
    }

    /** Перенос внутри недели: Встреча на новом месте, на плановой дате — строка «перенесена на». */
    @Test
    void meetingMovedWithinTheWeek() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        LocalDate thursday = TUESDAY.plusDays(2);
        MeetingAdjustment move = MeetingAdjustment.none(weekly.lesson().id(), TUESDAY)
                .withMove(new MeetingAdjustment.Move(thursday, LocalTime.of(18, 0), 90));

        assertThat(Meetings.between(List.of(weekly), List.of(move), MONDAY, SUNDAY)).singleElement()
                .satisfies(meeting -> {
                    assertThat(meeting.plannedDate()).isEqualTo(TUESDAY);
                    assertThat(meeting.date()).isEqualTo(thursday);
                    assertThat(meeting.start()).isEqualTo(LocalTime.of(18, 0));
                    assertThat(meeting.end()).isEqualTo(LocalTime.of(19, 30));
                    assertThat(meeting.moved()).isTrue();
                });
        assertThat(Meetings.movedAway(List.of(weekly), List.of(move), MONDAY, SUNDAY)).singleElement()
                .satisfies(movedAway -> {
                    assertThat(movedAway.plannedDate()).isEqualTo(TUESDAY);
                    assertThat(movedAway.plannedStart()).isEqualTo(FIVE_PM);
                    assertThat(movedAway.date()).isEqualTo(thursday);
                });
    }

    /**
     * Сценарий «Перенос во вторую неделю»: 27.10 перенесена на 02.11 — в неделе
     * 26.10 только строка «перенесена на», в неделе 02.11 — Встреча с пометкой
     * и обычная Встреча 03.11.
     */
    @Test
    void meetingMovedToTheNextWeek() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        LocalDate planned = LocalDate.of(2026, 10, 27);
        LocalDate moved = LocalDate.of(2026, 11, 2);
        MeetingAdjustment move = MeetingAdjustment.none(weekly.lesson().id(), planned)
                .withMove(new MeetingAdjustment.Move(moved, LocalTime.of(18, 0), 60));
        LocalDate thisMonday = LocalDate.of(2026, 10, 26);
        LocalDate nextMonday = moved;

        assertThat(Meetings.between(List.of(weekly), List.of(move), thisMonday, thisMonday.plusDays(6))).isEmpty();
        assertThat(Meetings.movedAway(List.of(weekly), List.of(move), thisMonday, thisMonday.plusDays(6)))
                .extracting(MovedAway::date).containsExactly(moved);

        List<Meeting> next = Meetings.between(List.of(weekly), List.of(move), nextMonday, nextMonday.plusDays(6));
        assertThat(next).extracting(Meeting::date).containsExactly(moved, LocalDate.of(2026, 11, 3));
        assertThat(next).extracting(Meeting::moved).containsExactly(true, false);
        assertThat(next.get(0).plannedDate()).isEqualTo(planned);
        assertThat(Meetings.movedAway(List.of(weekly), List.of(move), nextMonday, nextMonday.plusDays(6)))
                .isEmpty();
    }

    /** Перенос в прошлую неделю: Встреча видна там, где она теперь, а не на плановой дате. */
    @Test
    void meetingMovedToThePreviousWeek() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        LocalDate planned = TUESDAY.plusWeeks(1);
        LocalDate moved = SUNDAY;
        MeetingAdjustment move = MeetingAdjustment.none(weekly.lesson().id(), planned)
                .withMove(new MeetingAdjustment.Move(moved, FIVE_PM, 60));

        assertThat(Meetings.between(List.of(weekly), List.of(move), MONDAY, SUNDAY))
                .extracting(Meeting::date).containsExactly(TUESDAY, moved);
        assertThat(Meetings.between(List.of(weekly), List.of(move), MONDAY.plusWeeks(1), SUNDAY.plusWeeks(1)))
                .isEmpty();
        assertThat(Meetings.movedAway(List.of(weekly), List.of(move), MONDAY.plusWeeks(1), SUNDAY.plusWeeks(1)))
                .extracting(MovedAway::plannedDate).containsExactly(planned);
    }

    /** Повторный перенос меняет место той же Встречи: она одна, на последнем месте. */
    @Test
    void repeatedMoveKeepsOneMeeting() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        MeetingAdjustment first = MeetingAdjustment.none(weekly.lesson().id(), TUESDAY)
                .withMove(new MeetingAdjustment.Move(TUESDAY.plusDays(1), FIVE_PM, 60));
        MeetingAdjustment second = first.withMove(new MeetingAdjustment.Move(TUESDAY.plusDays(3), FIVE_PM, 60));

        assertThat(Meetings.between(List.of(weekly), List.of(second), MONDAY, SUNDAY))
                .extracting(Meeting::date).containsExactly(TUESDAY.plusDays(3));
    }

    /** Сценарий «Ученик не пришёл»: Встреча на своём месте с пометкой. */
    @Test
    void absenceMarksTheMeeting() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        MeetingAdjustment absence = MeetingAdjustment.none(weekly.lesson().id(), TUESDAY).withAbsent(true);

        assertThat(Meetings.between(List.of(weekly), List.of(absence), MONDAY, SUNDAY)).singleElement()
                .satisfies(meeting -> {
                    assertThat(meeting.date()).isEqualTo(TUESDAY);
                    assertThat(meeting.absent()).isTrue();
                    assertThat(meeting.cancelled()).isFalse();
                    assertThat(meeting.moved()).isFalse();
                });
    }

    /** Поправка на дату, которой правило не даёт (среда у Занятия по вторникам), не показывается. */
    @Test
    void adjustmentOnADateTheRuleDoesNotGiveIsIgnored() {
        ListedLesson weekly = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        LocalDate wednesday = TUESDAY.plusDays(1);
        MeetingAdjustment stray = MeetingAdjustment.none(weekly.lesson().id(), wednesday)
                .withMove(new MeetingAdjustment.Move(SUNDAY, FIVE_PM, 60));
        MeetingAdjustment cancelled = MeetingAdjustment.none(weekly.lesson().id(), wednesday.plusDays(1))
                .withCancelled(true);

        assertThat(Meetings.between(List.of(weekly), List.of(stray, cancelled), MONDAY, SUNDAY))
                .extracting(Meeting::date).containsExactly(TUESDAY);
        assertThat(Meetings.movedAway(List.of(weekly), List.of(stray, cancelled), MONDAY, SUNDAY)).isEmpty();
    }

    /** Поправка другого Занятия чужую Встречу не трогает. */
    @Test
    void adjustmentAppliesOnlyToItsLesson() {
        ListedLesson first = withStudent(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60), false);
        ListedLesson second = withGroup(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        MeetingAdjustment cancellation = MeetingAdjustment.none(first.lesson().id(), TUESDAY).withCancelled(true);

        assertThat(Meetings.between(List.of(first, second), List.of(cancellation), MONDAY, SUNDAY))
                .extracting(Meeting::cancelled).containsExactly(true, false);
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
