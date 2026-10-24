package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import ru.locus.Addresses;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Страница Помощника называет его доступность (спека {@code assistant-tools},
 * «Помощник без команды недоступен с причиной»; design.md agent-runner, Р11).
 * С тест-двойником команды и ключом из {@link TestAgentEnvironment} Помощник
 * доступен; с командой по несуществующему пути — свой контекст Spring,
 * в котором Локус всё равно поднимается.
 */
class AssistantPageTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Value("${locus.agent.version}")
    private String pinned;

    /** Сценарий «Всё на месте». */
    @Test
    void teacherSeesThatTheAssistantIsAvailable() {
        Browser.Page page = loggedIn(port, accounts.settled(Role.TEACHER)).get(Addresses.ASSISTANT);

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Помощник доступен: Claude Code " + pinned)
                .doesNotContain("Помощник недоступен");
    }

    /** Задачи через Помощника заводит и Администратор — страница открыта и ему. */
    @Test
    void administratorSeesThePageToo() {
        Browser.Page page = loggedIn(port, accounts.settled(Role.ADMINISTRATOR)).get(Addresses.ASSISTANT);

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Помощник доступен");
    }

    @Test
    void visitorWithoutLoginIsSentToTheLoginForm() {
        Browser.Page page = new Browser(port).get(Addresses.ASSISTANT);

        assertThat(page.redirectsTo(Addresses.LOGIN)).isTrue();
    }

    /** Пункта в шапке нет, пока на странице нечего делать (Р11). */
    @Test
    void headerHasNoAssistantItem() {
        String home = loggedIn(port, accounts.settled(Role.TEACHER)).get(Addresses.HOME).body();

        assertThat(home).doesNotContain("href=\"" + Addresses.ASSISTANT + "\"");
    }

    /** Сценарий «Команды нет». */
    @Nested
    @TestPropertySource(properties = "locus.agent.command=target/no-such-claude")
    class WithoutTheCommand {

        @LocalServerPort
        private int port;

        @Autowired
        private TestAccounts accounts;

        @Test
        void pageNamesTheCauseAndOtherPagesWork() {
            Browser browser = loggedIn(port, accounts.settled(Role.TEACHER));

            Browser.Page page = browser.get(Addresses.ASSISTANT);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.body()).contains("Помощник недоступен: команда Claude Code не найдена")
                    .contains("target/no-such-claude");

            assertThat(browser.get(Addresses.HOME).status()).isEqualTo(200);
            assertThat(browser.get(Addresses.PROBLEMS).status()).isEqualTo(200);
            assertThat(browser.get(Addresses.SCHEDULE).status()).isEqualTo(200);
        }
    }

    private static Browser loggedIn(int port, TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
