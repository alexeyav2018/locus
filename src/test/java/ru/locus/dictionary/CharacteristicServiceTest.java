package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.user.Role;

/**
 * Правила словаря Характеристик — те же, что у словаря Методов, и проверены
 * тем же набором сценариев.
 *
 * Симметрия проверок здесь — не переписанный дважды текст ради красоты:
 * два почти одинаковых сервиса расходятся тогда, когда правка доехала
 * до одного и не доехала до второго, и ловится это только тем, что оба
 * проверены одинаково.
 *
 * Здесь же проверяется независимость словарей: операция над одним второго
 * не касается.
 */
class CharacteristicServiceTest extends IntegrationTest {

    @Autowired
    private CharacteristicService characteristics;

    @Autowired
    private SolutionMethodService methods;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Запись заведена». */
    @Test
    void createdCharacteristicAppearsInItsDictionary() {
        String name = unique("Повышенной сложности");

        CharacteristicId id = characteristics.create(name);

        assertThat(characteristics.characteristic(id).name()).isEqualTo(name);
        assertThat(characteristics.all()).extracting(Characteristic::id).contains(id);
    }

    /** Сценарий «Пустое имя». */
    @Test
    void emptyNameCreatesNothing() {
        assertThatThrownBy(() -> characteristics.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
    }

    /** Сценарий «Пробелы по краям имени отсекаются». */
    @Test
    void surroundingSpacesAreTrimmedAndTheTrimmedNameIsTaken() {
        String name = unique("Повышенной сложности");

        CharacteristicId id = characteristics.create("  " + name + "  ");

        assertThat(characteristics.characteristic(id).name()).isEqualTo(name);
        assertThatThrownBy(() -> characteristics.create(name)).isInstanceOf(NameAlreadyTakenException.class);
    }

    /** Сценарий «Одноимённые Характеристики не сосуществуют». */
    @Test
    void sameNameIsRefused() {
        String name = unique("Повышенной сложности");
        characteristics.create(name);

        assertThatThrownBy(() -> characteristics.create(name))
                .isInstanceOf(NameAlreadyTakenException.class)
                .hasMessageContaining("занято");
    }

    /** Занятость имени — без учёта регистра, тем же правилом, что и индекс. */
    @Test
    void sameNameInAnotherCaseIsRefusedByTheServiceItself() {
        String name = unique("Повышенной сложности");
        characteristics.create(name);

        assertThatThrownBy(() -> characteristics.create(name.toUpperCase()))
                .isInstanceOf(NameAlreadyTakenException.class);
    }

    /** Сценарий «Запись переименована». */
    @Test
    void renamedCharacteristicKeepsItsIdentifierAndLeavesTheRestAlone() {
        CharacteristicId renamed = characteristics.create(unique("Повышенной сложности"));
        CharacteristicId untouched = characteristics.create(unique("Олимпиадная"));
        String untouchedName = characteristics.characteristic(untouched).name();
        String newName = unique("Базовая");

        characteristics.rename(renamed, newName);

        assertThat(characteristics.characteristic(renamed).name()).isEqualTo(newName);
        assertThat(characteristics.characteristic(untouched).name()).isEqualTo(untouchedName);
    }

    /** Сценарий «Новое имя занято». */
    @Test
    void renamingToATakenNameChangesNothing() {
        String taken = unique("Олимпиадная");
        characteristics.create(taken);
        String name = unique("Повышенной сложности");
        CharacteristicId id = characteristics.create(name);

        assertThatThrownBy(() -> characteristics.rename(id, taken)).isInstanceOf(NameAlreadyTakenException.class);

        assertThat(characteristics.characteristic(id).name()).isEqualTo(name);
    }

    /** Сценарий «Запись переименована в собственное имя». */
    @Test
    void savingACharacteristicUnderItsOwnNameGoesThrough() {
        String name = unique("Повышенной сложности");
        CharacteristicId id = characteristics.create(name);

        assertThatCode(() -> characteristics.rename(id, name)).doesNotThrowAnyException();

        assertThat(characteristics.characteristic(id).name()).isEqualTo(name);
    }

    /** Сценарий «Неиспользуемая запись удалена». */
    @Test
    void unusedCharacteristicIsDeletedAndTheRestStay() {
        CharacteristicId gone = characteristics.create(unique("Повышенной сложности"));
        CharacteristicId kept = characteristics.create(unique("Олимпиадная"));

        characteristics.delete(gone);

        assertThat(characteristics.all()).extracting(Characteristic::id).doesNotContain(gone).contains(kept);
    }

    /** Сценарий «Словари независимы». */
    @Test
    void oneNameIsAllowedInBothDictionariesAtOnce() {
        String name = unique("Разложение на множители");

        SolutionMethodId method = methods.create(name);
        CharacteristicId characteristic = characteristics.create(name);

        assertThat(methods.method(method).name()).isEqualTo(name);
        assertThat(characteristics.characteristic(characteristic).name()).isEqualTo(name);
        assertThat(methods.all()).extracting(SolutionMethod::id).contains(method);
        assertThat(characteristics.all()).extracting(Characteristic::id).contains(characteristic);
    }

    /** Сценарий «Удаление не задевает второй словарь». */
    @Test
    void deletingAMethodLeavesTheCharacteristicsAlone() {
        String name = unique("Разложение на множители");
        SolutionMethodId method = methods.create(name);
        CharacteristicId characteristic = characteristics.create(name);

        methods.delete(method);

        assertThat(characteristics.characteristic(characteristic).name())
                .as("одноимённая Характеристика удалением Метода не задета")
                .isEqualTo(name);
        assertThat(characteristics.all()).extracting(Characteristic::id).contains(characteristic);
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
