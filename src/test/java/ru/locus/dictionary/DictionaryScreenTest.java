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
 * Требование «Экран ведения словарей».
 *
 * Экран один на оба словаря: два списка рядом, действия — над выбранной
 * записью. Формы, повторённые у каждой строки, на словаре в сотню записей
 * дают сотню форм и прячут сам список, ради которого человек и пришёл.
 *
 * Отсутствие скриптов в шаблонах проверяется на всех шаблонах разом
 * ({@code TaxonomyScreenTest.noTemplateCarriesAScript}), и новый шаблон
 * попадает под ту же проверку.
 */
class DictionaryScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private SolutionMethodRepository methods;

    @Autowired
    private CharacteristicRepository characteristics;

    /** Сценарий «Оба словаря видны». */
    @Test
    void bothDictionariesAreShownWhole() {
        String method = unique("Разложение на множители");
        methods.create(method);
        String characteristic = unique("Повышенной сложности");
        characteristics.create(characteristic);

        Browser.Page page = loggedIn(Role.ADMINISTRATOR).get("/dictionaries");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .as("показаны оба списка")
                .contains(method)
                .contains(characteristic)
                .contains("Методы")
                .contains("Характеристики");
        assertThat(page.body()).as("разметка собрана на сервере").doesNotContain("th:text");
    }

    /** Сценарий «Оба словаря видны»: записи каждого списка идут по алфавиту. */
    @Test
    void entriesOfEachDictionaryAreShownInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        SolutionMethodId thirdMethod = methods.create("cc-" + mark);
        SolutionMethodId firstMethod = methods.create("aa-" + mark);
        SolutionMethodId secondMethod = methods.create("bb-" + mark);
        CharacteristicId thirdCharacteristic = characteristics.create("cc-" + mark);
        CharacteristicId firstCharacteristic = characteristics.create("aa-" + mark);
        CharacteristicId secondCharacteristic = characteristics.create("bb-" + mark);

        String body = loggedIn(Role.ADMINISTRATOR).get("/dictionaries").body();

        assertThat(body.indexOf(methodAnchor(firstMethod)))
                .as("порядок показа не совпадает с порядком заведения — он алфавитный")
                .isLessThan(body.indexOf(methodAnchor(secondMethod)));
        assertThat(body.indexOf(methodAnchor(secondMethod))).isLessThan(body.indexOf(methodAnchor(thirdMethod)));
        assertThat(body.indexOf(characteristicAnchor(firstCharacteristic)))
                .isLessThan(body.indexOf(characteristicAnchor(secondCharacteristic)));
        assertThat(body.indexOf(characteristicAnchor(secondCharacteristic)))
                .isLessThan(body.indexOf(characteristicAnchor(thirdCharacteristic)));
    }

    /** Сценарий «Действия относятся к выбранной записи». */
    @Test
    void actionsBelongToTheSelectedEntryAndAreNotInTheLists() {
        String name = unique("Разложение на множители");
        SolutionMethodId method = methods.create(name);
        methods.create(unique("Замена переменной"));
        characteristics.create(unique("Повышенной сложности"));

        String body = loggedIn(Role.ADMINISTRATOR).get("/dictionaries?method=" + method.value()).body();

        assertThat(body).as("справа показана выбранная запись").contains(name);
        assertThat(count(body, "Переименовать"))
                .as("действие одно на странице, а не по одному у каждой записи — в списках их нет")
                .isEqualTo(1);
        assertThat(count(body, "Удалить")).isEqualTo(1);
        assertThat(count(body, "class=\"selected\""))
                .as("выбранная запись подсвечена, и она одна")
                .isEqualTo(1);
    }

    /** Сценарий «Действия относятся к выбранной записи»: во втором словаре так же. */
    @Test
    void selectingACharacteristicOffersActionsOverItAlone() {
        methods.create(unique("Разложение на множители"));
        String name = unique("Повышенной сложности");
        CharacteristicId characteristic = characteristics.create(name);

        String body = loggedIn(Role.ADMINISTRATOR)
                .get("/dictionaries?characteristic=" + characteristic.value()).body();

        assertThat(body).contains(name);
        assertThat(count(body, "Переименовать")).isEqualTo(1);
        assertThat(count(body, "Удалить")).isEqualTo(1);
        assertThat(body)
                .as("действие адресовано словарю Характеристик, а не Методов")
                .contains("/dictionaries/characteristics/" + characteristic.value() + "/name");
    }

    /** Сценарий «Пока запись не выбрана». */
    @Test
    void withoutASelectedEntryOnlyTheCreationFormsAreOffered() {
        String method = unique("Разложение на множители");
        methods.create(method);
        String characteristic = unique("Повышенной сложности");
        characteristics.create(characteristic);

        String body = loggedIn(Role.ADMINISTRATOR).get("/dictionaries").body();

        assertThat(body).as("оба словаря показаны целиком").contains(method).contains(characteristic);
        assertThat(body)
                .as("действий над записью не предлагается")
                .doesNotContain("Переименовать")
                .doesNotContain("Удалить");
        assertThat(body)
                .as("завести новую запись в любой из словарей всё равно можно")
                .contains("Завести Метод")
                .contains("Завести Характеристику");
    }

    /** Сценарий «Возврат к записи после операции». */
    @Test
    void afterRenamingThePageComesBackToTheSameEntrySelected() {
        SolutionMethodId method = methods.create(unique("Разложение на множители"));
        CharacteristicId characteristic = characteristics.create(unique("Повышенной сложности"));
        Browser administrator = loggedIn(Role.ADMINISTRATOR);
        String renamedMethod = unique("Вынесение общего множителя");
        String renamedCharacteristic = unique("Олимпиадная");

        Browser.Page methodDone = administrator.postForm(
                "/dictionaries/methods/" + method.value() + "/name", Map.of("name", renamedMethod));
        Browser.Page characteristicDone = administrator.postForm(
                "/dictionaries/characteristics/" + characteristic.value() + "/name",
                Map.of("name", renamedCharacteristic));

        assertThat(methodDone.location())
                .as("параметр держит выбор, якорь — место в списке")
                .endsWith("/dictionaries?method=" + method.value() + "#method-" + method.value());
        assertThat(characteristicDone.location())
                .endsWith("/dictionaries?characteristic=" + characteristic.value()
                        + "#characteristic-" + characteristic.value());

        String body = administrator.get("/dictionaries?method=" + method.value()).body();
        assertThat(body).as("запись показана под новым именем и осталась выбранной").contains(renamedMethod);
        assertThat(count(body, "class=\"selected\"")).isEqualTo(1);
        assertThat(count(body, "Переименовать")).as("действия над ней доступны сразу").isEqualTo(1);
    }

    /** Сценарий «Возврат к записи после операции»: заведённая запись сразу выбрана. */
    @Test
    void afterCreationThePageComesBackToTheNewEntry() {
        String name = unique("Разложение на множители");

        Browser.Page done = loggedIn(Role.ADMINISTRATOR).postForm("/dictionaries/methods", Map.of("name", name));

        SolutionMethodId created = methods.findByName(name).orElseThrow().id();
        assertThat(done.location())
                .endsWith("/dictionaries?method=" + created.value() + "#method-" + created.value());
    }

    /** Сценарий «Выбрана запись, которой больше нет». */
    @Test
    void selectingAVanishedEntryStillShowsTheDictionaries() {
        String name = unique("Разложение на множители");
        methods.create(name);
        SolutionMethodId gone = methods.create(unique("Замена переменной"));
        methods.delete(gone);

        Browser.Page page = loggedIn(Role.ADMINISTRATOR).get("/dictionaries?method=" + gone.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).as("словари показываются").contains(name);
        assertThat(page.body()).as("об исчезнувшей записи сообщается").contains("не существует");
        assertThat(page.body())
                .as("действий над ней не предлагается")
                .doesNotContain("Переименовать")
                .doesNotContain("Удалить");
    }

    /** Сценарий «Учителю операций не предлагают». */
    @Test
    void teacherIsShownTheEntryWithoutAnyAction() {
        String method = unique("Разложение на множители");
        SolutionMethodId methodId = methods.create(method);
        String characteristic = unique("Повышенной сложности");
        characteristics.create(characteristic);

        String body = loggedIn(Role.TEACHER).get("/dictionaries?method=" + methodId.value()).body();

        assertThat(body).as("оба списка показаны").contains(method).contains(characteristic);
        assertThat(body)
                .as("действий правки на экране нет")
                .doesNotContain("Переименовать")
                .doesNotContain("Удалить")
                .doesNotContain("Завести Метод")
                .doesNotContain("Завести Характеристику");
    }

    /** Задача 4.6: со страницы после входа на словари можно перейти ссылкой. */
    @Test
    void dictionariesAreReachableFromTheHomePage() {
        String name = unique("Разложение на множители");
        methods.create(name);
        Browser browser = loggedIn(Role.TEACHER);

        assertThat(browser.get("/").body())
                .as("в навигации есть пункт словарей")
                .contains("href=\"/dictionaries\"");

        assertThat(browser.get("/dictionaries").body()).contains(name);
    }

    private static int count(String body, String fragment) {
        int found = 0;
        for (int at = body.indexOf(fragment); at >= 0; at = body.indexOf(fragment, at + fragment.length())) {
            found++;
        }
        return found;
    }

    private static String methodAnchor(SolutionMethodId id) {
        return "id=\"method-" + id.value() + "\"";
    }

    private static String characteristicAnchor(CharacteristicId id) {
        return "id=\"characteristic-" + id.value() + "\"";
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
