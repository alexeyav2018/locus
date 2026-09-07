package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Требование «Система отдаёт страницы, собранные на сервере».
 *
 * Запрос идёт по настоящему HTTP на поднятый сервер, а не через подмену:
 * проверяется в том числе то, что сервер вообще обслуживает запросы.
 */
class StartovayaStranicaTest extends IntegracionnyjTest {

    @LocalServerPort
    private int port;

    @Test
    void kornevojAdresOtdayotSobrannuyuNaServereStranicu() {
        String telo = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(telo)
                .as("разметка собрана на сервере: подстановка Thymeleaf уже выполнена")
                .contains("<h1>locus</h1>")
                .doesNotContain("th:text");
    }
}
