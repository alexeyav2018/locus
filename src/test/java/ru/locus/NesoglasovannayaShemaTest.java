package ru.locus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Требование «Несогласованная схема останавливает запуск».
 *
 * Контейнера здесь нет намеренно: проверяется поведение при базе, которой нет.
 * Приложение не должно подняться и начать обслуживать запросы на схеме,
 * о которой неизвестно, соответствует ли она коду.
 */
class NesoglasovannayaShemaTest {

    /** Порт, на котором заведомо никто не слушает. */
    private static final String NEDOSTUPNAYA_BAZA = "jdbc:postgresql://localhost:1/net_takoj_bazy";

    @Test
    void priNedostupnojBazePrilozhenieNeStartuet() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(LocusApplication.class)
                .properties(
                        "spring.datasource.url=" + NEDOSTUPNAYA_BAZA,
                        "spring.datasource.username=net",
                        "spring.datasource.password=net",
                        "spring.docker.compose.enabled=false",
                        "server.port=0")
                .run()
                .close())
                .as("запуск обрывается, а не продолжается на неизвестной схеме")
                .isInstanceOf(Exception.class);
    }
}
