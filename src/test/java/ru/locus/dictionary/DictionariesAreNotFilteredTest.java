package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.user.Role;

/**
 * Граница общего и личного: чтение словаря отдаёт всё, что есть в таблице.
 *
 * Отсутствие параметра владельца проверяется отражением
 * ({@link OwnerIsUnknownToDictionariesTest}), но выборочность можно завести
 * и без него — условием, зависящим от вошедшего. Здесь проверяется само
 * поведение: список равен содержимому таблицы, а два разных вошедших видят
 * один и тот же словарь.
 *
 * Ошибка была бы тихой: фильтр, применённый к библиотеке, не падает,
 * а показывает меньше — вплоть до пустого словаря, которым нечего размечать
 * (domain-model.md, «Граница общего и личного»).
 */
class DictionariesAreNotFilteredTest extends IntegrationTest {

    @Autowired
    private SolutionMethodRepository methods;

    @Autowired
    private CharacteristicRepository characteristics;

    @Autowired
    private JdbcClient database;

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    @Test
    void readingTheDictionaryReturnsEveryRowOfTheTable() {
        methods.create(unique("Разложение на множители"));
        characteristics.create(unique("Повышенной сложности"));

        assertThat(methods.findAll()).hasSize(rowsIn("solution_method"));
        assertThat(characteristics.findAll()).hasSize(rowsIn("characteristic"));
    }

    /** Сценарий «Словарь один для всех». */
    @Test
    void twoDifferentTeachersReadTheSameDictionaries() {
        SolutionMethodId method = methods.create(unique("Разложение на множители"));
        CharacteristicId characteristic = characteristics.create(unique("Повышенной сложности"));

        LoggedIn.as(Role.TEACHER);
        List<SolutionMethodId> firstMethods = ids(methods.findAll());
        List<CharacteristicId> firstCharacteristics = characteristicIds(characteristics.findAll());

        LoggedIn.as(Role.TEACHER, Role.ADMINISTRATOR);
        assertThat(ids(methods.findAll()))
                .as("библиотека общая: по владельцу она не фильтруется")
                .isEqualTo(firstMethods)
                .contains(method);
        assertThat(characteristicIds(characteristics.findAll()))
                .isEqualTo(firstCharacteristics)
                .contains(characteristic);
    }

    /** Сценарий «Словари независимы». */
    @Test
    void oneNameLivesInBothDictionariesAtOnce() {
        String name = unique("Разложение на множители");

        SolutionMethodId method = methods.create(name);
        CharacteristicId characteristic = characteristics.create(name);

        assertThat(methods.findByName(name).orElseThrow().id()).isEqualTo(method);
        assertThat(characteristics.findByName(name).orElseThrow().id()).isEqualTo(characteristic);
        assertThat(methods.findAll()).extracting(SolutionMethod::id).contains(method);
        assertThat(characteristics.findAll()).extracting(Characteristic::id).contains(characteristic);
    }

    private int rowsIn(String table) {
        return database.sql("select count(*) from " + table).query(Integer.class).single();
    }

    private static List<SolutionMethodId> ids(List<SolutionMethod> all) {
        return all.stream().map(SolutionMethod::id).toList();
    }

    private static List<CharacteristicId> characteristicIds(List<Characteristic> all) {
        return all.stream().map(Characteristic::id).toList();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
