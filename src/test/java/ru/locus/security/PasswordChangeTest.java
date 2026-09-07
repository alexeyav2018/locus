package ru.locus.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;
import ru.locus.user.UserRepository;

/**
 * Требования «Пароль, назначенный не владельцем записи, подлежит смене при
 * первом входе» и «Пользователь меняет свой пароль».
 *
 * Проверка идёт по настоящему HTTP: ограничение живёт в цепочке фильтров,
 * до проверки прав, и обход прямым адресом — именно то, что проверяется.
 */
class PasswordChangeTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private UserRepository users;

    /** Сценарий «Первый вход с назначенным паролем». */
    @Test
    void loginWithAnAssignedPasswordLeadsToThePasswordForm() {
        TestAccounts.Account account = accounts.withPasswordToChange(Role.TEACHER);
        Browser browser = new Browser(port);

        browser.logIn(account.login(), account.password());

        assertThat(browser.get("/").redirectsTo("/password"))
                .as("вход выполнен, но работа начинается со смены пароля")
                .isTrue();
        assertThat(browser.get("/password").status())
                .as("сама форма смены доступна")
                .isEqualTo(200);
    }

    /**
     * Сценарий «Попытка обойти смену пароля»: страница Администратора
     * запрашивается напрямую, минуя ссылки, и роли её допускают.
     */
    @Test
    void directRequestToAnAllowedPageStillLeadsToThePasswordForm() {
        TestAccounts.Account administrator = accounts.withPasswordToChange(Role.ADMINISTRATOR);
        Browser browser = new Browser(port);
        browser.logIn(administrator.login(), administrator.password());

        Browser.Page page = browser.get("/users");

        assertThat(page.redirectsTo("/password"))
                .as("проверка смены пароля стоит раньше проверки прав")
                .isTrue();
        assertThat(page.body()).doesNotContain("<table");
    }

    /** До смены пароля выход доступен: иначе из этого состояния не выйти. */
    @Test
    void logoutIsAvailableBeforeThePasswordIsChanged() {
        TestAccounts.Account account = accounts.withPasswordToChange(Role.TEACHER);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());

        assertThat(browser.logOut().redirectsTo("/login?logout")).isTrue();
    }

    /** Сценарии «Пароль сменён» и «Смена пароля». */
    @Test
    void afterTheChangeTheFlagIsClearedAndWorkGoesByRoles() {
        TestAccounts.Account account = accounts.withPasswordToChange(Role.TEACHER);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());

        Browser.Page changed = browser.postForm("/password", Map.of(
                "currentPassword", account.password(),
                "newPassword", "новый-пароль"));

        assertThat(changed.redirectsTo("/")).isTrue();
        assertThat(users.findById(account.id()).orElseThrow().passwordChangeRequired())
                .as("пометка снята")
                .isFalse();
        assertThat(browser.get("/").status())
                .as("дальнейшая работа идёт по правам его ролей")
                .isEqualTo(200);
    }

    /** Сценарий «Смена пароля»: следующий вход возможен только новым паролем. */
    @Test
    void nextLoginWorksOnlyWithTheNewPassword() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        browser.postForm("/password", Map.of(
                "currentPassword", account.password(),
                "newPassword", "совсем-другой-пароль"));
        browser.logOut();

        assertThat(new Browser(port).logIn(account.login(), account.password()).redirectsTo("/login?error"))
                .as("прежний пароль перестал действовать")
                .isTrue();
        assertThat(new Browser(port).logIn(account.login(), "совсем-другой-пароль").redirectsTo("/"))
                .as("новый пароль действует")
                .isTrue();
    }

    /** Сценарий «Текущий пароль указан неверно». */
    @Test
    void wrongCurrentPasswordLeavesThePasswordUnchanged() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());

        Browser.Page refused = browser.postForm("/password", Map.of(
                "currentPassword", "не тот пароль",
                "newPassword", "новый-пароль"));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body())
                .as("пользователю сообщается об отказе")
                .contains("Текущий пароль указан неверно");
        assertThat(new Browser(port).logIn(account.login(), account.password()).redirectsTo("/"))
                .as("пароль не заменён")
                .isTrue();
    }
}
