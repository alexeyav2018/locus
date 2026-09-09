package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Идентификатор узла — отдельный тип, а не голое число (ADR-0027).
 *
 * Проверяется, что значением он быть не может любым: нуля и отрицательного
 * идентификатора в базе не бывает, и подставленное вместо идентификатора
 * число из другого места должно падать здесь, а не разбираться потом
 * по пустому ответу.
 */
class TaxonomyNodeIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new TaxonomyNodeId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeIsNotAnIdentifier() {
        assertThatThrownBy(() -> new TaxonomyNodeId(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new TaxonomyNodeId(7)).isEqualTo(new TaxonomyNodeId(7));
    }
}
