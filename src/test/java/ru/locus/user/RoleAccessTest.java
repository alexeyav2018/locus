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
 * Требование «Права определяются набором ролей, активная роль не выбирается».
 *
 * Отказ проверяется обращением по прямому адресу, а не отсутствием ссылки
 * в разметке: доступность страницы определяется правами, а не тем, показана
 * ли на неё ссылка.
 */
class RoleAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private UserRepository users;

    /** Сценарий «Учитель обращается к учётным записям». */
    @Test
    void teacherIsRefusedTheListOfAccounts() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page page = teacher.get("/users");

        assertThat(page.status())
                .as("доступ не предоставляется, хотя вход выполнен")
                .isEqualTo(403);
        assertThat(page.body()).doesNotContain("<table");
    }

    /** Сценарий «Учитель обращается к операции Администратора». */
    @Test
    void teacherIsRefusedEveryAdministratorOperation() {
        TestAccounts.Account subject = accounts.settled();
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        assertThat(teacher.postForm("/users", Map.of(
                "login", "teacher-tried-" + UUID.randomUUID(),
                "initialPassword", "пароль")).status())
                .as("заведение записи")
                .isEqualTo(403);
        assertThat(teacher.postForm("/users/" + subject.id().value() + "/roles",
                Map.of("role", "ADMINISTRATOR", "assign", "true")).status())
                .as("назначение роли")
                .isEqualTo(403);
        assertThat(teacher.postForm("/users/" + subject.id().value() + "/password",
                Map.of("newPassword", "чужой-пароль")).status())
                .as("сброс чужого пароля")
                .isEqualTo(403);

        User untouched = users.findById(subject.id()).orElseThrow();
        assertThat(untouched.roles()).as("ни одна операция не выполнилась").isEmpty();
        assertThat(untouched.passwordChangeRequired()).isFalse();
    }

    /** Сценарий «Пользователь совмещает роли». */
    @Test
    void bothRolesWorkAtOnceAndNoRoleIsChosenAnywhere() {
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR, Role.TEACHER);
        Browser browser = new Browser(port);

        assertThat(browser.logIn(account.login(), account.password()).redirectsTo("/"))
                .as("вход ведёт прямо к работе: выбирать роль негде и незачем")
                .isTrue();

        assertThat(users.findById(account.id()).orElseThrow().roles())
                .containsExactlyInAnyOrder(Role.ADMINISTRATOR, Role.TEACHER);
        assertThat(browser.get("/users").status())
                .as("операции Администратора доступны, хотя роль Учителя тоже есть")
                .isEqualTo(200);
        assertThat(browser.get("/").status())
                .as("общие страницы доступны")
                .isEqualTo(200);
    }

    /** Сценарий «Пользователь без ролей». */
    @Test
    void userWithoutRolesLogsInButGetsNoOperation() {
        TestAccounts.Account account = accounts.settled();
        Browser browser = new Browser(port);

        assertThat(browser.logIn(account.login(), account.password()).redirectsTo("/"))
                .as("вход выполняется")
                .isTrue();
        assertThat(browser.get("/").status())
                .as("вошедший видит систему")
                .isEqualTo(200);
        assertThat(browser.get("/users").status())
                .as("ни одна операция ему не доступна")
                .isEqualTo(403);
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
