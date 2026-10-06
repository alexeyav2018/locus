package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.user.Role;

/**
 * Требование «Версия системы видна на каждой странице» (interface-navigation,
 * ADR-0043): подвал есть у вошедшего, у посетителя без входа и на странице
 * ошибки, и версия в нём — та, что в {@code pom.xml}.
 */
class VersionFooterTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private BuildProperties build;

    private Browser loggedIn() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }

    /** Сценарий «Подвал у вошедшего». */
    @Test
    void aLoggedInUserSeesTheVersionOnAPage() {
        assertThat(loggedIn().get("/").body())
                .contains("class=\"buildinfo\"")
                .contains("<span class=\"buildinfo-version\">" + build.getVersion() + "</span>");
    }

    /** Сценарий «Подвал на форме входа». */
    @Test
    void theLoginFormShowsTheVersionToAVisitorWithoutLogin() {
        assertThat(new Browser(port).get("/login").body())
                .contains("class=\"buildinfo\"")
                .contains(build.getVersion());
    }

    /** Сценарий «Подвал на странице ошибки». */
    @Test
    void anErrorPageShowsTheVersion() {
        // Посетителя без входа неизвестный адрес ведёт на форму входа (302), а не к странице ошибки;
        // подвал в error.html для остальных случаев сторожит EveryPageShowsTheVersionTest.
        Browser.Page page = loggedIn().get("/нет-такой-страницы");

        assertThat(page.status()).isEqualTo(404);
        assertThat(page.body()).contains(build.getVersion());
    }

    @Test
    void theVersionIsTheOneFromThePom() {
        assertThat(build.getVersion()).matches("\\d+\\.\\d+\\.\\d+");
    }
}
