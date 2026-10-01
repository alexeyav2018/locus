package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.user.Role;

/**
 * Требование «Страницы ошибок ведут дальше» (interface-navigation): своя
 * страница на русском с шапкой вошедшего и кнопками, без внутренностей.
 */
class ErrorPagesTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }

    /** Сценарий «Несуществующий адрес». */
    @Test
    void missingPageOfALoggedInUserHasTheHeaderAndWaysOut() {
        Browser.Page page = loggedIn(Role.TEACHER).get("/нет-такой-страницы");

        assertThat(page.status()).isEqualTo(404);
        assertThat(page.body())
                .contains("Страница не найдена")
                .contains("class=\"topbar\"")
                .contains("На главную")
                .contains("Назад")
                .doesNotContain("Whitelabel")
                .doesNotContain("Exception");
    }

    /** Сценарий «Нет доступа». */
    @Test
    void teacherOnAnAdministratorScreenSeesNoAccessAndNoContent() {
        Browser.Page page = loggedIn(Role.TEACHER).get("/users");

        assertThat(page.status()).isEqualTo(403);
        assertThat(page.body())
                .contains("Нет доступа")
                .contains("На главную")
                .doesNotContain("<table")
                .doesNotContain("Завести учётную запись");
    }

    /** Сценарий «Посетитель без входа». */
    @Test
    void errorOfAVisitorWithoutLoginHasNoHeader() {
        Browser.Page page = new Browser(port).get("/нет-такой-страницы");

        assertThat(page.status()).isIn(302, 403, 404);
        if (page.status() == 404) {
            assertThat(page.body()).doesNotContain("class=\"topbar\"");
        }
    }

    @Test
    void backFollowsAGivenInnerAddressOnly() {
        Browser browser = loggedIn(Role.TEACHER);

        assertThat(browser.get("/нет-такой-страницы?from=/students").body())
                .contains("href=\"/students\"");
        assertThat(browser.get("/нет-такой-страницы?from=//evil.example").body())
                .doesNotContain("evil.example");
    }
}
