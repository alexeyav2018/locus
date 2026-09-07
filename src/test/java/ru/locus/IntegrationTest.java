package ru.locus;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Основа интеграционных тестов: поднятое приложение на настоящем HTTP-порту
 * и чистая база в контейнере.
 *
 * Контейнер поднимается один раз на прогон и не гасится вручную — за уборкой
 * следит сам Testcontainers. Порт при этом остаётся неизменным, и кеш
 * контекстов Spring не разъезжается с базой.
 *
 * Режима «пропустить тесты, если Docker недоступен» здесь сознательно нет:
 * тест, который молча не выполнился, хуже отсутствующего — он создаёт
 * видимость проверки. Нет Docker — сборка падает.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresImage.fromComposeFile());

    static {
        POSTGRES.start();
    }
}
