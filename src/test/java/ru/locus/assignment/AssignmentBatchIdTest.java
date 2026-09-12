package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: идентификатор Раздачи — положительное число и отдельный тип.
 *
 * Те же соображения, что у {@link AssignmentIdTest}: Раздача и Задание
 * встречаются в одном вызове, и перепутанные числа отдали бы не ту запись
 * молча. Подставить сюда {@link AssignmentId} нельзя — это ошибка
 * компиляции, и проверять её тестом нечем.
 */
class AssignmentBatchIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new AssignmentBatchId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new AssignmentBatchId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new AssignmentBatchId(12).value()).isEqualTo(12);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new AssignmentBatchId(7)).isEqualTo(new AssignmentBatchId(7));
    }
}
