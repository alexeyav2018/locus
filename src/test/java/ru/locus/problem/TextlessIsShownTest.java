package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * «Без текста» видно, а не хранится (design.md изменения {@code problem-text},
 * Р11): пометка на странице Задачи, в списке Темы и в строке поиска, число
 * Задач без текста — в результате поиска.
 *
 * Пометка считается по видимому тексту значка {@code >без текста<}: строка
 * «Из них без текста — N» под ним не подпадает, и одно число отвечает
 * за строки, другое — за итог. Разметка целиком не сравнивается
 * (antipatterns.md, «Сравнение разметки страницы целиком»).
 */
class TextlessIsShownTest extends IntegrationTest {

    private static final String MARK = ">без текста<";

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemService problems;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Пометка в списке Темы»: помечена только Задача без текста. */
    @Test
    void onlyTheTextlessProblemIsMarkedOnTheTopic() {
        TaxonomyNodeId topic = library.topic();
        withText(topic);
        library.problem(topic);

        String tree = signedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + topic.value()).body();

        assertThat(marks(tree)).isEqualTo(1);
    }

    /** Сценарий «Число Задач без текста названо»: пять найдено, две без текста. */
    @Test
    void searchNamesHowManyAreTextlessAndMarksTheirRows() {
        TaxonomyNodeId topic = library.topic();
        withText(topic);
        withText(topic);
        withText(topic);
        library.problem(topic);
        library.problem(topic);

        String page = signedIn(Role.TEACHER).get("/problems?node=" + topic.value()).body();

        assertThat(page).contains("Найдено Задач: 5").contains("Из них без текста — 2");
        assertThat(marks(page)).isEqualTo(2);
    }

    /** Сценарий «Все найденные с текстом»: названо «ни одной», строк с пометкой нет. */
    @Test
    void searchSaysNoneWhenEveryFoundProblemHasText() {
        TaxonomyNodeId topic = library.topic();
        withText(topic);
        withText(topic);

        String page = signedIn(Role.TEACHER).get("/problems?node=" + topic.value()).body();

        assertThat(page).contains("Из них без текста — ни одной");
        assertThat(marks(page)).isZero();
    }

    /**
     * Сценарий «Учитель читает текст чужой Задачи»: Задачу заводил
     * Администратор, а Учитель видит пометку у Задачи без текста, ссылки
     * на {@code .md} у Задачи с текстом и скачивает файл — фильтра
     * по владельцу у библиотеки нет (ADR-0027). Правки ему не предложено.
     */
    @Test
    void teacherSeesTheMarkAndTheMarkdownOfAProblemHeDidNotCreate() {
        TaxonomyNodeId topic = library.topic();
        ProblemId textual = withText(topic);
        ProblemId textless = library.problem(topic);
        Browser teacher = signedIn(Role.TEACHER);

        String textualPage = teacher.get("/problems/" + textual.value()).body();
        String textlessPage = teacher.get("/problems/" + textless.value()).body();

        assertThat(marks(textlessPage)).isEqualTo(1);
        assertThat(marks(textualPage)).isZero();
        assertThat(textualPage)
                .contains("/problems/" + textual.value() + "/condition.md")
                .contains("/problems/" + textual.value() + "/solution.md")
                .doesNotContain("Править Задачу");
        assertThat(textlessPage).doesNotContain(".md");
        assertThat(teacher.get("/problems/" + textual.value() + "/condition.md").status()).isEqualTo(200);
    }

    private ProblemId withText(TaxonomyNodeId topic) {
        return problems.create(ExamPart.SECOND, List.of(topic), List.of(library.method()),
                List.of(), "Решите уравнение $x + 1 = 3$.", "Ответ: $x = 2$.", null, null);
    }

    private static int marks(String page) {
        return page.split(MARK, -1).length - 1;
    }

    private Browser signedIn(Role role) {
        TestAccounts.Account account = accounts.settled(role);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
