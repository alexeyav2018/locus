package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: идентификатор Задания — положительное число и отдельный тип.
 *
 * Те же соображения, что у {@link ru.locus.student.StudentIdTest}: рядом
 * с идентификатором Задания в каждом вызове стоят идентификаторы владельца,
 * Ученика и Раздачи, и нуль означал бы неинициализированное поле, которое
 * обнаружилось бы чужим Заданием. Подставить сюда {@link ru.locus.student.StudentId}
 * нельзя — это ошибка компиляции, и проверять её тестом нечем.
 */
class AssignmentIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new AssignmentId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new AssignmentId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new AssignmentId(12).value()).isEqualTo(12);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new AssignmentId(7)).isEqualTo(new AssignmentId(7));
    }
}
