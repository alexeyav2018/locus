package ru.locus.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;

/**
 * Требование «Администратор ведёт учётные записи».
 *
 * Запросы идут по настоящему HTTP от лица вошедшего Администратора.
 */
class UserAdministrationTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private UserRepository users;

    /** Сценарий «Заведение пользователя». */
    @Test
    void administratorCreatesAnAccountWhosePasswordMustBeChanged() {
        Browser administrator = loggedInAdministrator();
        String login = uniqueLogin();

        Browser.Page created = administrator.postForm("/users", Map.of(
                "login", login,
                "initialPassword", "начальный-пароль"));

        assertThat(created.redirectsTo("/users")).isTrue();
        User account = users.findByLogin(login).orElseThrow();
        assertThat(account.passwordChangeRequired())
                .as("пароль назначен не владельцем записи и подлежит смене")
                .isTrue();
        assertThat(account.roles()).as("роли назначаются отдельно").isEmpty();
        assertThat(administrator.get("/users").body()).contains(login);
    }

    /** Сценарий «Имя входа занято». */
    @Test
    void takenLoginIsRefusedAndReported() {
        Browser administrator = loggedInAdministrator();
        String login = uniqueLogin();
        administrator.postForm("/users", Map.of("login", login, "initialPassword", "пароль"));

        Browser.Page refused = administrator.postForm("/users", Map.of(
                "login", login,
                "initialPassword", "другой-пароль"));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body())
                .as("Администратору сказано, что имя занято")
                .contains("уже занято");
        assertThat(users.findAll().stream().filter(user -> user.login().equals(login)).count())
                .as("вторая запись не создана")
                .isEqualTo(1);
    }

    /** Сценарий «Назначение и снятие роли». */
    @Test
    void rolesAreAssignedAndRevoked() {
        Browser administrator = loggedInAdministrator();
        TestAccounts.Account subject = accounts.settled();
        String roles = "/users/" + subject.id().value() + "/roles";

        administrator.postForm(roles, Map.of("role", "TEACHER", "assign", "true"));
        assertThat(users.findById(subject.id()).orElseThrow().roles()).containsExactly(Role.TEACHER);

        administrator.postForm(roles, Map.of("role", "TEACHER", "assign", "false"));
        assertThat(users.findById(subject.id()).orElseThrow().roles()).isEmpty();
    }

    /** Сценарий «Сброс пароля». */
    @Test
    void resetPasswordReplacesTheOldOneAndRequiresAChange() {
        Browser administrator = loggedInAdministrator();
        TestAccounts.Account subject = accounts.settled();

        administrator.postForm("/users/" + subject.id().value() + "/password",
                Map.of("newPassword", "назначенный-пароль"));

        assertThat(users.findById(subject.id()).orElseThrow().passwordChangeRequired()).isTrue();
        assertThat(new Browser(port).logIn(subject.login(), subject.password()).redirectsTo("/login?error"))
                .as("прежний пароль перестал действовать")
                .isTrue();
        assertThat(new Browser(port).logIn(subject.login(), "назначенный-пароль").redirectsTo("/"))
                .as("новый пароль действует")
                .isTrue();
    }

    /** Заведённый Администратором пользователь сразу попадает на форму смены. */
    @Test
    void createdUserLogsInWithTheAssignedPasswordAndLandsOnThePasswordForm() {
        Browser administrator = loggedInAdministrator();
        String login = uniqueLogin();
        administrator.postForm("/users", Map.of("login", login, "initialPassword", "назначенный-пароль"));

        Browser created = new Browser(port);
        assertThat(created.logIn(login, "назначенный-пароль").redirectsTo("/"))
                .as("вход назначенным паролем проходит")
                .isTrue();
        assertThat(created.get("/").redirectsTo("/password"))
                .as("и сразу приводит на форму смены пароля")
                .isTrue();
    }

    private Browser loggedInAdministrator() {
        TestAccounts.Account administrator = accounts.settled(Role.ADMINISTRATOR);
        Browser browser = new Browser(port);
        browser.logIn(administrator.login(), administrator.password());
        return browser;
    }

    private static String uniqueLogin() {
        return "created-" + UUID.randomUUID();
    }
}
