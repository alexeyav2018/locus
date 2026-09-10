package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.Role;

/**
 * Экран поиска: форма условий, найденное под ней и возврат условий в форму.
 *
 * Сравнивается видимый человеку текст, а не разметка целиком: сравнение всей
 * страницы ломается от переноса строки и от лишнего пробела, и чинить его
 * приходится на каждой правке шаблона (antipatterns.md, «Сравнение разметки
 * страницы целиком»). Исключение — отметки {@code selected} и {@code checked}:
 * они человеку видны, а в тексте страницы их нет вовсе, поэтому искать их
 * приходится в разметке — но точечно, вокруг своего поля.
 *
 * Отбор как таковой проверен ниже, в {@code ProblemSearchRepositoryTest}
 * и {@code ProblemSearchTest}. Здесь проверяется ровно то, что добавляет
 * экран: условия доехали из адреса до поиска, найденное показано, условия
 * вернулись в форму.
 *
 * Ходит сюда Учитель, а не Администратор: поиск делается ради него, и роль
 * ему для этого не нужна. Обстановку при этом заводит Администратор — иначе
 * {@link ProblemService#create} откажет по правам.
 *
 * Тесты идут на одной базе, поэтому каждый заводит обстановку себе сам
 * и сужает поиск своим узлом там, где сравнивает список целиком.
 */
class ProblemSearchScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemService problems;

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private SolutionMethodService methods;

    @Autowired
    private CharacteristicService characteristics;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /**
     * Сценарий «Способ по умолчанию»: экран открывается вошедшим Учителем,
     * и у обоих условий отмечено «любое из».
     *
     * Умолчание расширяющее не по вкусу: сужающее показало бы меньше, чем
     * есть, и этого не видно (ADR-0031). Проверяются оба условия — умолчание,
     * поставленное одному и забытое другому, выглядело бы работающим.
     */
    @Test
    void searchScreenOpensWithBothModesSetToAny() {
        String page = teacher().get("/problems").body();

        assertThat(page).contains("Поиск Задач").contains("все сразу").contains("любое из");
        assertThat(checkedMode(page, "methodMode"))
                .as("умолчание у Методов — расширяющее")
                .isEqualTo("ANY");
        assertThat(checkedMode(page, "characteristicMode"))
                .as("умолчание у Характеристик — расширяющее")
                .isEqualTo("ANY");
    }

    /**
     * Сценарий «Отбор по Разделу находит Задачи дальних потомков» — с экрана.
     *
     * Задача висит на три уровня ниже выбранного узла: на глубине один
     * «обошли только детей» неотличимо от правильного ответа.
     */
    @Test
    void searchBySectionShowsProblemOfADistantDescendant() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId deep = library.topic(library.topic(library.topic(section)));
        ProblemId problem = library.problem(List.of(deep), List.of(library.method()), List.of(), ExamPart.SECOND);

        String page = teacher().get("/problems?node=" + section.value()).body();

        assertThat(page).contains("№ " + problem.value()).contains("Найдено Задач: 1");
    }

    /** Сценарий «Условий не задано»: показаны заведённые Задачи. */
    @Test
    void searchWithoutConditionsShowsTheProblemsOfTheLibrary() {
        ProblemId first = library.problem(library.topic());
        ProblemId second = library.problem(library.topic());

        String page = teacher().get("/problems").body();

        assertThat(page).contains("№ " + first.value()).contains("№ " + second.value());
    }

    /**
     * Сценарии «Все сразу» и «Любое из» — с экрана, на одной и той же
     * обстановке: разница между списками должна получаться от переключателя,
     * а не от разных Задач.
     */
    @Test
    void allModeNarrowsTheListAndAnyModeWidensIt() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId first = library.method();
        SolutionMethodId second = library.method();
        ProblemId both = library.problem(List.of(topic), List.of(first, second), List.of(), ExamPart.SECOND);
        ProblemId onlyFirst = library.problem(List.of(topic), List.of(first), List.of(), ExamPart.SECOND);
        String conditions = "/problems?node=" + topic.value()
                + "&method=" + first.value() + "&method=" + second.value() + "&methodMode=";

        String narrowed = teacher().get(conditions + "ALL").body();
        String widened = teacher().get(conditions + "ANY").body();

        assertThat(narrowed)
                .contains("№ " + both.value())
                .doesNotContain("№ " + onlyFirst.value())
                .contains("Найдено Задач: 1");
        assertThat(widened)
                .contains("№ " + both.value())
                .contains("№ " + onlyFirst.value())
                .contains("Найдено Задач: 2");
    }

    /**
     * Сценарий «Ничего не найдено»: пустой результат назван словами.
     *
     * Пустая страница читалась бы как поломка, а «все сразу» на двух Методах
     * возвращает пусто совершенно законно.
     */
    @Test
    void emptyResultIsSaidInWordsAndNotShownAsAnEmptyPage() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId chosen = library.method();
        SolutionMethodId never = library.method();
        library.problem(List.of(topic), List.of(chosen), List.of(), ExamPart.SECOND);

        String page = teacher().get("/problems?node=" + topic.value()
                + "&method=" + chosen.value() + "&method=" + never.value() + "&methodMode=ALL").body();

        assertThat(page)
                .contains("По этим условиям Задач нет.")
                .doesNotContain("Найдено Задач:");
    }

    /**
     * Сценарий «Строка результата»: номер, подпись, Часть и вся разметка —
     * именами, а Темы полными путями.
     *
     * Подпись даётся через сервис: {@link TestLibrary} заводит Задачи без
     * подписи, а различать Задачу с подписью и без неё экран обязан.
     */
    @Test
    void resultRowShowsNumberCaptionPartAndMarkupByName() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();
        ProblemId problem = problems.create("Ященко, вариант 12", ExamPart.SECOND,
                List.of(topic), List.of(method), List.of(characteristic),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF));

        String page = teacher().get("/problems?node=" + topic.value()).body();

        assertThat(page)
                .contains("№ " + problem.value())
                .contains("Ященко, вариант 12")
                .contains("вторая часть")
                .as("Тема названа полным путём: одинаковые имена под разными родителями законны")
                .contains("Темы: " + taxonomy.node(section).name() + " / " + taxonomy.node(topic).name())
                .contains("Методы: " + methods.method(method).name())
                .contains("Характеристики: " + characteristics.characteristic(characteristic).name())
                .as("из строки открывается сама Задача")
                .contains("/problems/" + problem.value() + "\"");
    }

    /**
     * Сценарий «Условия видны вместе с результатом».
     *
     * Не удобство: один и тот же набор условий даёт при разных способах
     * разные списки, и оба выглядят правдоподобно. Не видя способа, учитель
     * не знает, каким прочтением получил свои строки.
     */
    @Test
    void chosenConditionsComeBackToTheFormSelectedAndChecked() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId first = library.method();
        SolutionMethodId second = library.method();
        CharacteristicId characteristic = library.characteristic();
        library.problem(List.of(topic), List.of(first, second), List.of(characteristic), ExamPart.FIRST);

        String page = teacher().get("/problems?node=" + topic.value()
                + "&method=" + first.value() + "&method=" + second.value() + "&methodMode=ALL"
                + "&characteristic=" + characteristic.value() + "&characteristicMode=ANY"
                + "&part=FIRST").body();

        assertThat(selected(page, "node")).containsExactly(String.valueOf(topic.value()));
        assertThat(selected(page, "method"))
                .containsExactlyInAnyOrder(String.valueOf(first.value()), String.valueOf(second.value()));
        assertThat(selected(page, "characteristic")).containsExactly(String.valueOf(characteristic.value()));
        assertThat(selected(page, "part")).containsExactly("FIRST");
        assertThat(checkedMode(page, "methodMode")).isEqualTo("ALL");
        assertThat(checkedMode(page, "characteristicMode"))
                .as("способы у Методов и Характеристик независимы")
                .isEqualTo("ANY");
    }

    /**
     * Задача 3.4: с экрана дерева ведёт ссылка на поиск по выбранному узлу.
     *
     * Ссылка нужна именно с узлом: поиск, открытый на всей библиотеке, терял
     * бы место, где учитель стоял, и тот выбирал бы узел заново — в списке
     * из сотни путей.
     */
    @Test
    void treeScreenLinksToSearchAtTheChosenNode() {
        TaxonomyNodeId topic = library.topic();

        String tree = teacher().get("/taxonomy?node=" + topic.value()).body();

        assertThat(tree)
                .contains("Искать по библиотеке")
                .contains("/problems?node=" + topic.value());
    }

    /**
     * Отмеченный способ соединения — тот, у чьей радиокнопки стоит
     * {@code checked}. Ищется он в разметке, потому что в тексте страницы
     * отметки нет: человек видит точку, а тест — атрибут.
     */
    private static String checkedMode(String page, String field) {
        Matcher radio = Pattern.compile(
                "name=\"" + field + "\" value=\"(\\w+)\"( checked=\"checked\")?").matcher(oneLine(page));
        String checked = null;
        while (radio.find()) {
            if (radio.group(2) != null) {
                checked = radio.group(1);
            }
        }
        return checked;
    }

    /**
     * Выбранные значения одного списка. Список берётся свой, а не вся
     * страница: варианты разных списков нумеруются своими
     * последовательностями, и совпадение номеров между ними — обычное дело.
     */
    private static List<String> selected(String page, String field) {
        String markup = oneLine(page);
        int start = markup.indexOf("name=\"" + field + "\"");
        assertThat(start).as("на странице есть список «" + field + "»").isNotNegative();
        String list = markup.substring(start, markup.indexOf("</select>", start));
        Matcher option = Pattern.compile("value=\"([^\"]*)\" selected=\"selected\"").matcher(list);
        return option.results().map(found -> found.group(1)).toList();
    }

    /**
     * Разметка в одну строку с одиночными пробелами. Thymeleaf переносит
     * подставленный атрибут на новую строку — ровно туда, где стоял
     * {@code th:selected} в шаблоне, — и образец, написанный с одним
     * пробелом, перестал бы совпадать от переноса в шаблоне, а не от порчи
     * страницы.
     */
    private static String oneLine(String page) {
        return page.replaceAll("\\s+", " ");
    }

    private Browser teacher() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
