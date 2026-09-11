package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 3.1: идентификатор Ученика — положительное число и отдельный тип.
 *
 * Нуль и отрицательное значение отклоняются не ради строгости: рядом
 * с идентификатором Ученика в каждом вызове стоит идентификатор владельца,
 * и значение вроде нуля означало бы, что где-то сложилось
 * неинициализированное поле — а обнаружилось бы это чужой карточкой.
 * Идентификатор Пользователя сюда не подставить вовсе — это ошибка
 * компиляции, и проверять её тестом нечем.
 */
class StudentIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new StudentId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new StudentId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new StudentId(12).value()).isEqualTo(12);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new StudentId(7)).isEqualTo(new StudentId(7));
    }
}
