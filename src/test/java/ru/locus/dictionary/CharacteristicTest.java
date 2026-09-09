package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Состав записи словаря Характеристик — тот же, что у Метода, и проверяется
 * так же: словари устроены одинаково, а роли у них разные.
 */
class CharacteristicTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new CharacteristicId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeIsNotAnIdentifier() {
        assertThatThrownBy(() -> new CharacteristicId(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new CharacteristicId(7)).isEqualTo(new CharacteristicId(7));
    }

    @Test
    void emptyNameIsNotACharacteristic() {
        assertThatThrownBy(() -> new Characteristic(new CharacteristicId(1), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> new Characteristic(new CharacteristicId(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordCarriesNothingButIdentifierAndName() {
        assertThat(Characteristic.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "name");
    }

    /**
     * Общего суперкласса и общего интерфейса у записей словарей нет
     * (design.md, «Две таблицы и две записи»): сведённые под общий тип, они
     * прошли бы туда, где ждут только Метод, — а Метод участвует в измерении
     * владения, Характеристика нет.
     */
    @Test
    void dictionaryEntriesShareNoCommonType() {
        assertThat(Characteristic.class.getInterfaces()).isEmpty();
        assertThat(SolutionMethod.class.getInterfaces()).isEmpty();
        assertThat(Characteristic.class.getSuperclass()).isEqualTo(Record.class);
        assertThat(SolutionMethod.class.getSuperclass()).isEqualTo(Record.class);
    }
}
