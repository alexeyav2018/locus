package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Состав записи словаря Методов: идентификатор и имя, и больше ничего.
 *
 * Идентификатор — отдельный тип, а не голое число: подставленное вместо него
 * число из другого места должно падать здесь, а не разбираться потом
 * по пустому ответу. Идентификатор Характеристики сюда не подставить вовсе —
 * это ошибка компиляции, и проверять её тестом нечем.
 */
class SolutionMethodTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new SolutionMethodId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeIsNotAnIdentifier() {
        assertThatThrownBy(() -> new SolutionMethodId(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new SolutionMethodId(7)).isEqualTo(new SolutionMethodId(7));
    }

    @Test
    void emptyNameIsNotAMethod() {
        assertThatThrownBy(() -> new SolutionMethod(new SolutionMethodId(1), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> new SolutionMethod(new SolutionMethodId(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordCarriesNothingButIdentifierAndName() {
        assertThat(SolutionMethod.class.getRecordComponents())
                .as("ни родителя, ни принадлежности Теме или Разделу у записи нет (ADR-0010)")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "name");
    }
}
