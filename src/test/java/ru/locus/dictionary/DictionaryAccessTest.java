package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Требование «Ведёт словари только Администратор, читают все вошедшие».
 *
 * Отказ проверяется обращением по прямому адресу, а не отсутствием кнопки
 * в разметке: доступность операции определяется правами, а не тем, показана
 * ли на неё форма.
 *
 * Записи заводятся через репозиторий: тесту нужно подготовить обстановку,
 * а не проверить права на её подготовку.
 */
class DictionaryAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private SolutionMethodRepository methods;

    @Autowired
    private CharacteristicRepository characteristics;

    /** Сценарий «Учитель читает словари». */
    @Test
    void teacherSeesBothDictionariesWhole() {
        String method = unique("Разложение на множители");
        methods.create(method);
        String characteristic = unique("Повышенной сложности");
        characteristics.create(characteristic);

        Browser.Page page = loggedIn(Role.TEACHER).get("/dictionaries");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains(method).contains(characteristic);
    }

    /** Сценарий «Учитель пытается изменить словарь». */
    @Test
    void teacherIsRefusedEveryEditingOperationInBothDictionaries() {
        String methodName = unique("Разложение на множители");
        SolutionMethodId method = methods.create(methodName);
        String characteristicName = unique("Повышенной сложности");
        CharacteristicId characteristic = characteristics.create(characteristicName);
        Browser teacher = loggedIn(Role.TEACHER);

        assertThat(teacher.postForm("/dictionaries/methods", Map.of("name", unique("Замена переменной"))).status())
                .as("заведение Метода")
                .isEqualTo(403);
        assertThat(teacher.postForm("/dictionaries/methods/" + method.value() + "/name",
                Map.of("name", "Переименовано учителем")).status())
                .as("переименование Метода")
                .isEqualTo(403);
        assertThat(teacher.postForm("/dictionaries/methods/" + method.value() + "/deletion", Map.of()).status())
                .as("удаление Метода")
                .isEqualTo(403);

        assertThat(teacher.postForm("/dictionaries/characteristics",
                Map.of("name", unique("Олимпиадная"))).status())
                .as("заведение Характеристики")
                .isEqualTo(403);
        assertThat(teacher.postForm("/dictionaries/characteristics/" + characteristic.value() + "/name",
                Map.of("name", "Переименовано учителем")).status())
                .as("переименование Характеристики")
                .isEqualTo(403);
        assertThat(teacher.postForm("/dictionaries/characteristics/" + characteristic.value() + "/deletion",
                Map.of()).status())
                .as("удаление Характеристики")
                .isEqualTo(403);

        assertThat(methods.findById(method).orElseThrow().name())
                .as("словарь остался прежним")
                .isEqualTo(methodName);
        assertThat(characteristics.findById(characteristic).orElseThrow().name()).isEqualTo(characteristicName);
    }

    /** Сценарий «Словарь один для всех». */
    @Test
    void twoTeachersSeeTheSameDictionaries() {
        String name = unique("Разложение на множители");
        methods.create(name);

        Browser.Page first = loggedIn(Role.TEACHER).get("/dictionaries");
        Browser.Page second = loggedIn(Role.TEACHER).get("/dictionaries");

        assertThat(first.body())
                .as("библиотека общая: по владельцу она не фильтруется")
                .contains(name);
        assertThat(second.body()).contains(name);
    }

    /** Сценарий «Невошедший к словарям не допускается». */
    @Test
    void withoutLoginTheDictionariesAreNotShown() {
        String name = unique("Разложение на множители");
        methods.create(name);

        Browser.Page page = new Browser(port).get("/dictionaries");

        assertThat(page.redirectsTo("/login")).as("обращение приводит к форме входа").isTrue();
        assertThat(page.body()).doesNotContain(name);
    }

    @Test
    void administratorEditsBothDictionaries() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);
        String method = unique("Разложение на множители");
        String characteristic = unique("Повышенной сложности");

        Browser.Page createdMethod = administrator.postForm("/dictionaries/methods", Map.of("name", method));
        Browser.Page createdCharacteristic =
                administrator.postForm("/dictionaries/characteristics", Map.of("name", characteristic));

        assertThat(createdMethod.status()).as("операция выполнена, ответ — переадресация").isEqualTo(302);
        assertThat(createdCharacteristic.status()).isEqualTo(302);
        assertThat(methods.findAll()).extracting(SolutionMethod::name).contains(method);
        assertThat(characteristics.findAll()).extracting(Characteristic::name).contains(characteristic);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
