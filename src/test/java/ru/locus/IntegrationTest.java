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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {IntegrationTest.DRAFTS, IntegrationTest.AGENT_COMMAND, IntegrationTest.AGENT_TIME_LIMIT,
                IntegrationTest.AGENT_WORKSPACES})
public abstract class IntegrationTest {

    /**
     * Своя папка черновиков сборки: при старте она очищается целиком,
     * и папка по умолчанию — та же, что у {@code mvn spring-boot:run}, —
     * теряла бы черновики запущенного рядом приложения.
     */
    public static final String DRAFTS = "locus.problem.draft.directory=target/test-assembly-drafts";

    /**
     * Помощник — тест-двойник команды Claude Code из рабочего дерева: копия
     * в {@code target/test-classes} теряет признак исполняемого файла.
     */
    public static final String AGENT_COMMAND = "locus.agent.command=src/test/resources/assistant/claude-double";

    /** Предел времени Реплики, чтобы зависание двойника проверялось за секунды. */
    public static final String AGENT_TIME_LIMIT = "locus.agent.time-limit=3s";

    /** Свои рабочие места Помощника, не те, что у {@code mvn spring-boot:run}. */
    public static final String AGENT_WORKSPACES = "locus.agent.workspaces=target/test-agent-workspaces";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresImage.fromComposeFile());

    static {
        POSTGRES.start();
    }
}
