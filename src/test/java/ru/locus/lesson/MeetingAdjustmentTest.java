package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ru.locus.lesson.MeetingAdjustment.Move;

/**
 * Задача 1.2: правила Поправки Встречи и вопрос «даёт ли правило Встречу
 * в дату» — модульно, без базы (ADR-0048).
 */
class MeetingAdjustmentTest {

    private static final LessonId LESSON = new LessonId(1);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);
    private static final Move TO_THURSDAY = new Move(LocalDate.of(2026, 10, 8), LocalTime.of(18, 0), 60);

    @Test
    void emptyAdjustmentMeansTheRule() {
        assertThat(MeetingAdjustment.none(LESSON, TUESDAY).isEmpty()).isTrue();
        assertThat(MeetingAdjustment.none(LESSON, TUESDAY).withAbsent(true).isEmpty()).isFalse();
        assertThat(MeetingAdjustment.none(LESSON, TUESDAY).withMove(TO_THURSDAY).withMove(null).isEmpty()).isTrue();
    }

    @Test
    void cancellationExcludesMoveAndAbsence() {
        assertThatThrownBy(() -> new MeetingAdjustment(LESSON, TUESDAY, true, TO_THURSDAY, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeetingAdjustment(LESSON, TUESDAY, true, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationDropsTheMoveAndMoveDropsTheCancellation() {
        MeetingAdjustment moved = MeetingAdjustment.none(LESSON, TUESDAY).withMove(TO_THURSDAY);

        MeetingAdjustment cancelled = moved.withCancelled(true);
        assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.move()).isNull();

        MeetingAdjustment movedAgain = cancelled.withMove(TO_THURSDAY);
        assertThat(movedAgain.cancelled()).isFalse();
        assertThat(movedAgain.move()).isEqualTo(TO_THURSDAY);
    }

    @Test
    void cancellingAMeetingWithAbsenceIsRefused() {
        MeetingAdjustment absent = MeetingAdjustment.none(LESSON, TUESDAY).withAbsent(true);

        assertThatThrownBy(() -> absent.withCancelled(true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moveDurationIsBetweenOneAndTwelveHours() {
        assertThatThrownBy(() -> new Move(TUESDAY, FIVE_PM, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Move(TUESDAY, FIVE_PM, 721)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Move.of(TUESDAY, FIVE_PM, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Move(TUESDAY, FIVE_PM, 90).end()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    void weeklyRuleOccursOnItsDayWithinItsDates() {
        LessonTiming weekly = LessonTiming.weekly(TUESDAY, TUESDAY.plusWeeks(2), FIVE_PM, 60);

        assertThat(weekly.occursOn(TUESDAY)).isTrue();
        assertThat(weekly.occursOn(TUESDAY.plusWeeks(2))).isTrue();
        assertThat(weekly.occursOn(TUESDAY.plusWeeks(3))).isFalse();
        assertThat(weekly.occursOn(TUESDAY.minusWeeks(1))).isFalse();
        assertThat(weekly.occursOn(TUESDAY.plusDays(1))).isFalse();
        assertThat(LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60).occursOn(TUESDAY.plusWeeks(100))).isTrue();
    }

    @Test
    void onceRuleOccursOnlyOnItsDate() {
        LessonTiming once = LessonTiming.once(TUESDAY, FIVE_PM, 60);

        assertThat(once.occursOn(TUESDAY)).isTrue();
        assertThat(once.occursOn(TUESDAY.plusWeeks(1))).isFalse();
    }
}
