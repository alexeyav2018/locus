package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.problem.ProblemId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Требования «Контекстный возврат» и «У каждой формы есть выход без браузера»
 * (interface-navigation): «назад» несёт условия поиска, чужой адрес не
 * принимается, «Отмена» формы ведёт туда, откуда пришли.
 */
class ReturnNavigationTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private TaxonomyNodeId topic;
    private ProblemId problem;
    private Browser teacher;
    private Browser administrator;

    @BeforeEach
    void setUp() {
        LoggedIn.as(Role.ADMINISTRATOR);
        topic = library.topic();
        problem = library.problem(topic);
        teacher = browser(Role.TEACHER);
        administrator = browser(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Условия поиска не теряются». */
    @Test
    void searchResultsCarryTheirOwnAddressAndTheCardGoesBackToIt() {
        String results = teacher.get("/problems?node=" + topic.value()).body();
        assertThat(results).contains("/problems/" + problem.value() + "?from=/problems?node%3D" + topic.value());

        String card = teacher.get("/problems/" + problem.value() + "?from=%2Fproblems%3Fpart%3DFIRST").body();
        assertThat(card).contains("<a href=\"/problems?part=FIRST\">← К поиску Задач</a>");
    }

    /** Сценарий «Возврат без контекста». */
    @Test
    void withoutFromBackGoesToTheSectionDefault() {
        String card = teacher.get("/problems/" + problem.value()).body();

        assertThat(card).contains("<a href=\"/problems\">← К поиску Задач</a>");
    }

    /** Сценарий «Чужой адрес не принимается». */
    @Test
    void foreignFromIsIgnoredAndTheDefaultIsUsed() {
        for (String evil : new String[] {"https%3A%2F%2Fevil.example", "%2F%2Fevil.example", "javascript%3Aalert(1)",
                "%2Fa%0Ab", "%2F%5Cevil.example"}) {
            String card = teacher.get("/problems/" + problem.value() + "?from=" + evil).body();

            // Значение остаётся лишь закодированной частью query дальнейших ссылок —
            // ни одна ссылка страницы не ведёт на чужой адрес.
            assertThat(card).as("from=%s", evil)
                    .doesNotContain("href=\"http").doesNotContain("href=\"//").doesNotContain("href=\"javascript")
                    .contains("<a href=\"/problems\">← К поиску Задач</a>");
        }
    }

    /** Сценарий «Отмена создания Задачи». */
    @Test
    void cancelOfTheNewProblemFormLeadsBackToTheNode() {
        String form = administrator.get("/problems/new?topic=" + topic.value()
                + "&from=%2Ftaxonomy%3Fnode%3D" + topic.value()).body();

        assertThat(form).contains("<a class=\"btn\" href=\"/taxonomy?node=" + topic.value() + "\">Отмена</a>");
    }

    @Test
    void treeLinksToProblemsCarryTheNodeAddress() {
        String tree = administrator.get("/taxonomy?node=" + topic.value()).body();

        assertThat(tree).contains("/problems/" + problem.value() + "?from=/taxonomy?node%3D" + topic.value());
    }

    private Browser browser(Role role) {
        TestAccounts.Account account = accounts.settled(role);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
