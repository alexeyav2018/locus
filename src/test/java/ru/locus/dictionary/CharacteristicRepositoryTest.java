package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;

/**
 * Хранение словаря Характеристик — проверяется симметрично словарю Методов
 * и на том же наборе свойств.
 *
 * Симметрия здесь не ради единообразия: два почти одинаковых репозитория —
 * это риск, что правка доедет до одного и не доедет до второго. Ловится
 * он тем, что оба проверены одинаково, а не тем, что оба прочитаны глазами.
 */
class CharacteristicRepositoryTest extends IntegrationTest {

    @Autowired
    private CharacteristicRepository characteristics;

    @Test
    void createdCharacteristicIsReadBackByItsIdentifier() {
        String name = unique("Повышенной сложности");

        CharacteristicId id = characteristics.create(name);

        Characteristic found = characteristics.findById(id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.name()).isEqualTo(name);
    }

    @Test
    void wholeDictionaryIsReadBack() {
        CharacteristicId id = characteristics.create(unique("Повышенной сложности"));

        assertThat(characteristics.findAll()).extracting(Characteristic::id).contains(id);
    }

    @Test
    void characteristicsComeInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        characteristics.create("cc-" + mark);
        characteristics.create("aa-" + mark);
        characteristics.create("bb-" + mark);

        List<String> mine = characteristics.findAll().stream()
                .map(Characteristic::name)
                .filter(name -> name.endsWith(mark))
                .toList();

        assertThat(mine).containsExactly("aa-" + mark, "bb-" + mark, "cc-" + mark);
    }

    @Test
    void nameIsFoundRegardlessOfCase() {
        String name = unique("Повышенной сложности");
        CharacteristicId id = characteristics.create(name);

        assertThat(characteristics.findByName(name.toUpperCase()).orElseThrow().id()).isEqualTo(id);
        assertThat(characteristics.findByName(unique("Ничего такого"))).isEmpty();
    }

    @Test
    void renamedCharacteristicKeepsItsIdentifier() {
        CharacteristicId id = characteristics.create(unique("Повышенной сложности"));
        String renamed = unique("Олимпиадная");

        characteristics.rename(id, renamed);

        Characteristic found = characteristics.findById(id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.name()).isEqualTo(renamed);
    }

    @Test
    void deletedCharacteristicDisappears() {
        CharacteristicId gone = characteristics.create(unique("Повышенной сложности"));
        CharacteristicId kept = characteristics.create(unique("Олимпиадная"));

        characteristics.delete(gone);

        assertThat(characteristics.findById(gone)).isEmpty();
        assertThat(characteristics.findById(kept)).isPresent();
    }

    /** Задача 1.2: уникальность имени по своему словарю держит индекс. */
    @Test
    void sameNameIsRefusedBySchema() {
        String name = unique("Повышенной сложности");
        characteristics.create(name);

        assertThatThrownBy(() -> characteristics.create(name)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Задача 1.2: индекс функциональный — регистр имени не спасает от дубля. */
    @Test
    void sameNameInAnotherCaseIsRefusedBySchemaToo() {
        String name = unique("Повышенной сложности");
        characteristics.create(name);

        assertThatThrownBy(() -> characteristics.create(name.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownIdentifierIsNotFound() {
        long free = characteristics.findAll().stream()
                .mapToLong(characteristic -> characteristic.id().value())
                .max()
                .orElse(0) + 1_000_000;

        assertThat(characteristics.findById(new CharacteristicId(free))).isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
