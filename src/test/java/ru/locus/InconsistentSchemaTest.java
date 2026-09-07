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
class InconsistentSchemaTest {

    /** Порт, на котором заведомо никто не слушает. */
    private static final String UNREACHABLE_DATABASE = "jdbc:postgresql://localhost:1/no-such-database";

    @Test
    void applicationDoesNotStartOnAnUnreachableDatabase() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(LocusApplication.class)
                .properties(
                        "spring.datasource.url=" + UNREACHABLE_DATABASE,
                        "spring.datasource.username=none",
                        "spring.datasource.password=none",
                        "spring.docker.compose.enabled=false",
                        "server.port=0")
                .run()
                .close())
                .as("запуск обрывается, а не продолжается на неизвестной схеме")
                .isInstanceOf(Exception.class);
    }
}
