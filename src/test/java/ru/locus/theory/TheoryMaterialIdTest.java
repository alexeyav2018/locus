package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 3.1: идентификатор Теоретического материала — положительное число
 * и отдельный тип.
 *
 * Нуль и отрицательное значение отклоняются не ради строгости: материал
 * ссылается на узел дерева, рядом с идентификатором материала в вызовах
 * стоит идентификатор узла, и значение вроде нуля означало бы, что где-то
 * сложилось неинициализированное поле — а обнаружилось бы это материалом
 * на чужом узле.
 */
class TheoryMaterialIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new TheoryMaterialId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new TheoryMaterialId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new TheoryMaterialId(12).value()).isEqualTo(12);
    }
}
