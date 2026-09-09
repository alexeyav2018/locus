package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyRepository;
import ru.locus.user.Role;

/**
 * Экраны Задачи: список на выбранной Теме, объяснение на Разделе, форма
 * разметки и страница просмотра.
 *
 * Сравнивается видимый человеку текст, а не разметка целиком: сравнение всей
 * страницы ломается от переноса строки и от лишнего пробела, и чинить его
 * приходится на каждой правке шаблона (antipatterns.md, «Сравнение разметки
 * страницы целиком»).
 */
class ProblemScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemService problems;

    @Autowired
    private SolutionMethodService methods;

    @Autowired
    private TaxonomyRepository nodes;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Список Задач Темы». */
    @Test
    void chosenTopicShowsItsProblemsWithNumberCaptionAndMarks() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        ProblemId problem = problems.create("Ященко, вариант 12", ExamPart.SECOND,
                List.of(topic), List.of(method), List.of(),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF));

        String page = administrator().get("/taxonomy?node=" + topic.value()).body();

        assertThat(page)
                .contains("Задачи Темы")
                .contains("№ " + problem.value())
                .contains("Ященко, вариант 12")
                .contains("вторая часть")
                .as("действие заведения предлагается прямо здесь")
                .contains("Завести Задачу");
    }

    /** Сценарий «Тема без Задач». */
    @Test
    void topicWithoutProblemsSaysSoAndStillOffersToCreateOne() {
        TaxonomyNodeId topic = library.topic();

        String page = administrator().get("/taxonomy?node=" + topic.value()).body();

        assertThat(page).contains("Задач на этой Теме нет.").contains("Завести Задачу");
    }

    /**
     * Сценарий «Выбран Раздел»: объяснение вместо пустого списка.
     *
     * Пустой список читался бы как «здесь ничего нет», и Администратор
     * заводил бы дубли уже существующих Задач.
     */
    @Test
    void chosenSectionExplainsThatProblemsLiveOnTopics() {
        TaxonomyNodeId section = library.section();

        String page = administrator().get("/taxonomy?node=" + section.value()).body();

        assertThat(page)
                .contains("Задачи несут только Темы")
                .as("списка Задач у Раздела нет вовсе")
                .doesNotContain("Задачи Темы")
                .doesNotContain("Задач на этой Теме нет.");
    }

    /** Сценарий «Просмотр Задачи». */
    @Test
    void problemPageShowsMarkupNumberCaptionAndBothLinks() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        String topicName = nodes.findById(topic).orElseThrow().name();
        String methodName = methods.method(method).name();
        ProblemId problem = problems.create("Ященко, вариант 12", ExamPart.SECOND,
                List.of(topic), List.of(method), List.of(library.characteristic()),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF));

        String page = administrator().get("/problems/" + problem.value()).body();

        assertThat(page)
                .contains("Задача № " + problem.value())
                .contains("Ященко, вариант 12")
                .contains("вторая часть")
                .contains(topicName)
                .contains(methodName)
                .contains("PDF условия")
                .contains("PDF решения");
    }

    /** Сценарий «Задача без подписи»: в списке она различима номером. */
    @Test
    void problemWithoutACaptionIsStillTellableByItsNumber() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        assertThat(administrator().get("/taxonomy?node=" + topic.value()).body())
                .contains("№ " + problem.value());
        assertThat(administrator().get("/problems/" + problem.value()).body())
                .contains("Подписи нет: Задача различается номером.");
    }

    /**
     * Задача 7.4: Методы Темы показываются первыми, полный словарь —
     * отдельным списком, и выбор не ограничен ни тем, ни другим.
     */
    @Test
    void markupFormShowsTheTopicsMethodsFirstAndTheWholeDictionaryToo() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId seen = library.method();
        SolutionMethodId neverSeenHere = library.method();
        library.problem(topic, seen);

        String form = administrator().get("/problems/new?topic=" + topic.value()).body();

        String seenName = methods.method(seen).name();
        String otherName = methods.method(neverSeenHere).name();
        assertThat(form).contains("Уже встречались в этой Теме:").contains("Весь словарь Методов:");
        assertThat(form.indexOf("Уже встречались в этой Теме:"))
                .as("выборка Темы стоит раньше полного словаря")
                .isLessThan(form.indexOf("Весь словарь Методов:"));
        assertThat(form).contains(seenName);
        assertThat(form)
                .as("Метод, в Теме не встречавшийся, выбрать всё равно можно")
                .contains(otherName);
    }

    /** Сценарий «Тема без Задач»: выборки нет, полный словарь остаётся доступным. */
    @Test
    void markupFormOnAnEmptyTopicOffersTheWholeDictionary() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();

        String form = administrator().get("/problems/new?topic=" + topic.value()).body();

        assertThat(form).doesNotContain("Уже встречались в этой Теме:");
        assertThat(form).contains("Весь словарь Методов:").contains(methods.method(method).name());
    }

    /**
     * Форма разметки предлагает только Темы: Задач на Разделах не бывает.
     *
     * Проверяется значение варианта выбора, а не имя: имя Раздела всё равно
     * видно в форме — оно входит в полный путь его же потомка, и это
     * правильно, потому что Тему выбирают именно по пути от корня.
     */
    @Test
    void markupFormOffersTopicsOnly() {
        TaxonomyNodeId section = library.section();
        TaxonomyNodeId child = nodes.findChildren(section).get(0).id();

        String topicChoices = topicChoicesOf(administrator().get("/problems/new").body());

        assertThat(topicChoices)
                .as("Раздел выбрать нельзя")
                .doesNotContain("value=\"" + section.value() + "\"");
        assertThat(topicChoices)
                .as("а его потомок — Тема, и он предлагается")
                .contains("value=\"" + child.value() + "\"");
    }

    /** Правка идёт той же формой, заполненной существующей Задачей. */
    @Test
    void editingFormComesFilledWithTheProblem() {
        ProblemId problem = library.problem(library.topic());

        String form = administrator().get("/problems/" + problem.value() + "/edit").body();

        assertThat(form).contains("Задача № " + problem.value()).contains("Сохранить");
    }

    /** Задача 7.6: с главной страницы к Задачам есть дорога. */
    @Test
    void homePageLeadsToTheLibrary() {
        Browser browser = administrator();

        Browser.Page home = browser.get("/");

        assertThat(home.body())
                .as("главная называет каталог Задач построенным")
                .contains("каталог Задач")
                .contains("Задачи");
        assertThat(browser.get("/taxonomy").status())
                .as("переход по ссылке работает")
                .isEqualTo(200);
    }

    /** Удаление возвращает на Тему, где Задача лежала. */
    @Test
    void deletingAProblemReturnsToItsTopic() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        Browser.Page deleted = administrator()
                .postForm("/problems/" + problem.value() + "/deletion", Map.of());

        assertThat(deleted.redirectsTo("#node-" + topic.value()))
                .as("место, где работал Администратор, не теряется")
                .isTrue();
    }

    /**
     * Список выбора Тем — только он: варианты Методов и Характеристик нумеруются
     * своими последовательностями, и «нет варианта с таким номером» на всей
     * странице означало бы совпадение с записью другого словаря.
     */
    private static String topicChoicesOf(String page) {
        int start = page.indexOf("name=\"topics\"");
        assertThat(start).as("на странице есть выбор Темы").isNotNegative();
        return page.substring(start, page.indexOf("</select>", start));
    }

    private Browser administrator() {
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
