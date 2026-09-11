package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 5.1: идентификатор Группы — положительное число и отдельный тип.
 *
 * Те же соображения, что у {@link StudentIdTest}: рядом с идентификатором
 * Группы в каждом вызове стоят идентификаторы владельца и Учеников состава,
 * и нуль означал бы неинициализированное поле, которое обнаружилось бы
 * чужой Группой. Подставить сюда {@link StudentId} нельзя — это ошибка
 * компиляции, и проверять её тестом нечем.
 */
class GroupIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new GroupId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new GroupId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new GroupId(12).value()).isEqualTo(12);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new GroupId(7)).isEqualTo(new GroupId(7));
    }
}
