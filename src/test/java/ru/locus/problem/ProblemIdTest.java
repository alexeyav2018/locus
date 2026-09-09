package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: идентификатор Задачи — положительное число и отдельный тип.
 *
 * Нуль и отрицательное значение отклоняются не ради строгости: этот же
 * идентификатор служит номером Задачи, обещанным человеку, и «Задача № 0»
 * означала бы, что где-то сложилось неинициализированное поле.
 */
class ProblemIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new ProblemId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new ProblemId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new ProblemId(12).value()).isEqualTo(12);
    }
}
