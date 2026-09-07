package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Требование «Система отдаёт страницы, собранные на сервере».
 *
 * Запрос идёт по настоящему HTTP на поднятый сервер, а не через подмену:
 * проверяется в том числе то, что сервер вообще обслуживает запросы.
 *
 * Корневой адрес страницу кому попало больше не отдаёт: единственная
 * страница, доступная без входа, — форма входа, и серверная сборка разметки
 * проверяется теперь на ней.
 */
class ServerRenderedPageTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    /** Сценарий «Обращение к корневому адресу без входа». */
    @Test
    void rootAddressWithoutLoginLeadsToTheServerRenderedLoginForm() {
        Browser.Page form = new Browser(port).follow("/");

        assertThat(form.status()).isEqualTo(200);
        assertThat(form.contentType()).contains("text/html");
        assertThat(form.body())
                .as("разметка собрана на сервере: подстановка Thymeleaf уже выполнена")
                .contains("<h1>Вход</h1>")
                .doesNotContain("th:action");
    }

    /** Сценарий «Обращение к корневому адресу». */
    @Test
    void rootAddressGivesALoggedInUserAServerRenderedPage() {
        TestAccounts.Account account = accounts.settled();
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());

        Browser.Page home = browser.get("/");

        assertThat(home.status()).isEqualTo(200);
        assertThat(home.contentType()).contains("text/html");
        assertThat(home.body())
                .as("разметка собрана на сервере, а не построена в браузере")
                .contains("<h1>Locus</h1>")
                .contains(account.login())
                .doesNotContain("th:text");
    }
}
