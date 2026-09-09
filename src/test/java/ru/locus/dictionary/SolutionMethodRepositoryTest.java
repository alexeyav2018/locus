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
 * Хранение словаря Методов: запись читается обратно, список приходит
 * по алфавиту, а уникальность имени без учёта регистра держит схема.
 *
 * Проверка идёт на настоящей базе в контейнере — подмены репозитория нет:
 * половина проверяемого здесь живёт в SQL и в индексах, а не в Java.
 *
 * Тесты идут на одной базе, поэтому имя каждой записи своё: имя уникально
 * на весь словарь.
 */
class SolutionMethodRepositoryTest extends IntegrationTest {

    @Autowired
    private SolutionMethodRepository methods;

    @Test
    void createdMethodIsReadBackByItsIdentifier() {
        String name = unique("Разложение на множители");

        SolutionMethodId id = methods.create(name);

        SolutionMethod found = methods.findById(id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.name()).isEqualTo(name);
    }

    @Test
    void wholeDictionaryIsReadBack() {
        SolutionMethodId id = methods.create(unique("Разложение на множители"));

        assertThat(methods.findAll()).extracting(SolutionMethod::id).contains(id);
    }

    @Test
    void methodsComeInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        methods.create("cc-" + mark);
        methods.create("aa-" + mark);
        methods.create("bb-" + mark);

        List<String> mine = methods.findAll().stream()
                .map(SolutionMethod::name)
                .filter(name -> name.endsWith(mark))
                .toList();

        assertThat(mine)
                .as("порядок показа не совпадает с порядком заведения — он алфавитный")
                .containsExactly("aa-" + mark, "bb-" + mark, "cc-" + mark);
    }

    /**
     * Поиск по имени — тем же сравнением, что держит уникальный индекс:
     * проверка в сервисе и последний рубеж в схеме должны отказывать
     * на одних и тех же именах.
     */
    @Test
    void nameIsFoundRegardlessOfCase() {
        String name = unique("Разложение на множители");
        SolutionMethodId id = methods.create(name);

        assertThat(methods.findByName(name.toUpperCase()).orElseThrow().id()).isEqualTo(id);
        assertThat(methods.findByName(name.toLowerCase()).orElseThrow().id()).isEqualTo(id);
        assertThat(methods.findByName(unique("Ничего такого"))).isEmpty();
    }

    @Test
    void renamedMethodKeepsItsIdentifier() {
        SolutionMethodId id = methods.create(unique("Разложение на множители"));
        String renamed = unique("Вынесение общего множителя");

        methods.rename(id, renamed);

        SolutionMethod found = methods.findById(id).orElseThrow();
        assertThat(found.id()).as("разметка ссылается на идентификатор, и за именем она не следует").isEqualTo(id);
        assertThat(found.name()).isEqualTo(renamed);
    }

    @Test
    void deletedMethodDisappears() {
        SolutionMethodId gone = methods.create(unique("Разложение на множители"));
        SolutionMethodId kept = methods.create(unique("Замена переменной"));

        methods.delete(gone);

        assertThat(methods.findById(gone)).isEmpty();
        assertThat(methods.findById(kept)).isPresent();
    }

    /** Задача 1.2: уникальность имени по всему словарю держит индекс. */
    @Test
    void sameNameIsRefusedBySchema() {
        String name = unique("Разложение на множители");
        methods.create(name);

        assertThatThrownBy(() -> methods.create(name)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Задача 1.2: индекс функциональный, {@code lower(name)}. Обычный
     * уникальный индекс пропустил бы «Разложение» рядом с «разложением» —
     * и статистика владения разошлась бы по двум ячейкам (ADR-0010).
     */
    @Test
    void sameNameInAnotherCaseIsRefusedBySchemaToo() {
        String name = unique("Разложение на множители");
        methods.create(name);

        assertThatThrownBy(() -> methods.create(name.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownIdentifierIsNotFound() {
        long free = methods.findAll().stream()
                .mapToLong(method -> method.id().value())
                .max()
                .orElse(0) + 1_000_000;

        assertThat(methods.findById(new SolutionMethodId(free))).isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
