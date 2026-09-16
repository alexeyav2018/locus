package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: идентификатор Работы — положительное число и отдельный тип.
 *
 * Те же соображения, что у {@link ru.locus.assignment.AssignmentIdTest}:
 * рядом с ним в каждом вызове стоят идентификаторы владельца, Задания
 * и файла, и нуль означал бы неинициализированное поле, которое
 * обнаружилось бы чужой Работой.
 */
class StudentWorkIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new StudentWorkId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new StudentWorkId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new StudentWorkId(12).value()).isEqualTo(12);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new StudentWorkId(7)).isEqualTo(new StudentWorkId(7));
    }
}
