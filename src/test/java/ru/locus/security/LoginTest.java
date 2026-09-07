package ru.locus.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;

/**
 * Требования «Работа возможна только после входа», «Вход по имени входа
 * и паролю», «Завершение сеанса».
 *
 * Запросы идут по настоящему HTTP через {@link Browser}: проверяется в том
 * числе цепочка фильтров, в которой и живёт половина проверяемого.
 */
class LoginTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    /** Сценарий «Верные имя входа и пароль». */
    @Test
    void correctCredentialsStartASessionAndLeadToTheHomePage() {
        TestAccounts.Account account = accounts.settled();
        Browser browser = new Browser(port);

        Browser.Page loggedIn = browser.logIn(account.login(), account.password());

        assertThat(loggedIn.redirectsTo("/"))
                .as("вход принят и ведёт на главную страницу, а не обратно на форму")
                .isTrue();

        Browser.Page home = browser.get("/");
        assertThat(home.status()).isEqualTo(200);
        assertThat(home.contentType()).contains("text/html");
    }

    /** Сценарий «Неверный пароль». */
    @Test
    void wrongPasswordIsRefused() {
        TestAccounts.Account account = accounts.settled();
        Browser browser = new Browser(port);

        assertThat(browser.logIn(account.login(), "не тот пароль").redirectsTo("/login?error")).isTrue();
        assertThat(browser.get("/").redirectsTo("/login"))
                .as("сеанс не начат")
                .isTrue();
    }

    /** Сценарий «Несуществующее имя входа»: отказ неотличим от предыдущего. */
    @Test
    void unknownLoginIsRefusedWithTheSameMessageAsAWrongPassword() {
        TestAccounts.Account account = accounts.settled();

        String afterWrongPassword = refusalMessage(account.login(), "не тот пароль");
        String afterUnknownLogin = refusalMessage("такого-имени-нет", account.password());

        assertThat(afterUnknownLogin)
                .as("по сообщению нельзя понять, что именно неверно")
                .isEqualTo(afterWrongPassword);
    }

    /** Сценарий «Обращение к странице без входа». */
    @Test
    void requestWithoutLoginLeadsToTheLoginForm() {
        Browser browser = new Browser(port);

        assertThat(browser.get("/").redirectsTo("/login")).isTrue();

        Browser.Page form = browser.follow("/");
        assertThat(form.status()).isEqualTo(200);
        assertThat(form.body())
                .as("посетитель получает форму входа, а не запрошенную страницу")
                .contains("name=\"username\"")
                .contains("name=\"password\"");
    }

    /** Сценарий «Обращение к адресу, на который нет ссылки». */
    @Test
    void requestToAnUnlinkedAddressAlsoLeadsToTheLoginForm() {
        Browser browser = new Browser(port);

        assertThat(browser.get("/users").redirectsTo("/login"))
                .as("страница ведения учётных записей закрыта и без входа")
                .isTrue();
        assertThat(browser.get("/на-эту-страницу-нет-ссылки").redirectsTo("/login"))
                .as("доступность определяется правами, а не наличием ссылки")
                .isTrue();
    }

    /** Сценарий «Выход». */
    @Test
    void logOutEndsTheSession() {
        TestAccounts.Account account = accounts.settled();
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        assertThat(browser.get("/").status()).isEqualTo(200);

        assertThat(browser.logOut().redirectsTo("/login?logout")).isTrue();

        assertThat(browser.get("/").redirectsTo("/login"))
                .as("после выхода любая страница снова требует входа")
                .isTrue();
    }

    /**
     * Текст, который видит человек после отказа. Сравнивать разметку целиком
     * нельзя: в ней есть токен CSRF, у каждого сеанса свой.
     */
    private String refusalMessage(String login, String password) {
        Browser browser = new Browser(port);
        browser.logIn(login, password);
        Browser.Page form = browser.get("/login?error");

        Matcher paragraph = Pattern.compile("<p>([^<]+)</p>").matcher(form.body());
        assertThat(paragraph.find()).as("отказ показан человеку").isTrue();
        return paragraph.group(1).trim();
    }
}
