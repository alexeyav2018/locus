package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Задача 1.2: `Distribution` — четыре числа, и «семь и один» не равно
 * «один и семь» (ADR-0013).
 */
class DistributionTest {

    @Test
    void ofCountsSevenMasteredAndOneNotMastered() {
        Distribution distribution = Distribution.of(
                List.of(MasteryStatus.MASTERED, MasteryStatus.MASTERED, MasteryStatus.MASTERED,
                        MasteryStatus.MASTERED, MasteryStatus.MASTERED, MasteryStatus.MASTERED,
                        MasteryStatus.MASTERED, MasteryStatus.NOT_MASTERED));

        assertThat(distribution).isEqualTo(new Distribution(7, 0, 1, 0));
    }

    @Test
    void oneMasteredAndSevenNotMasteredIsNotTheSameAsTheOtherWayAround() {
        Distribution sevenAndOne = Distribution.of(
                List.of(MasteryStatus.MASTERED, MasteryStatus.MASTERED, MasteryStatus.MASTERED,
                        MasteryStatus.MASTERED, MasteryStatus.MASTERED, MasteryStatus.MASTERED,
                        MasteryStatus.MASTERED, MasteryStatus.NOT_MASTERED));
        Distribution oneAndSeven = Distribution.of(
                List.of(MasteryStatus.MASTERED, MasteryStatus.NOT_MASTERED, MasteryStatus.NOT_MASTERED,
                        MasteryStatus.NOT_MASTERED, MasteryStatus.NOT_MASTERED, MasteryStatus.NOT_MASTERED,
                        MasteryStatus.NOT_MASTERED, MasteryStatus.NOT_MASTERED));

        assertThat(oneAndSeven).isEqualTo(new Distribution(1, 0, 7, 0));
        assertThat(sevenAndOne)
                .as("«семь и один» и «один и семь» — разные распределения")
                .isNotEqualTo(oneAndSeven);
    }

    @Test
    void plusAddsComponentByComponent() {
        Distribution first = new Distribution(1, 2, 3, 4);
        Distribution second = new Distribution(4, 3, 2, 1);

        assertThat(first.plus(second)).isEqualTo(new Distribution(5, 5, 5, 5));
    }

    @Test
    void emptyHasNoCells() {
        assertThat(Distribution.empty().cells()).isZero();
    }

    @Test
    void negativeCountIsRefused() {
        assertThatThrownBy(() -> new Distribution(-1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
