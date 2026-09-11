package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Требование «Ведёт Задачи только Администратор, читают все вошедшие», часть
 * о том, что библиотека общая: сценарий «Задачи, заведённые другим
 * Пользователем».
 *
 * Проверка идёт через настоящий вход двумя разными Учителями, а не вызовом
 * репозитория: забытый фильтр не падает, а тихо показывает меньше — и заметить
 * это можно только сравнив то, что видят разные люди.
 */
class ProblemsAreNotFilteredTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    /** Сценарий «Задачи, заведённые другим Пользователем». */
    @Test
    void twoDifferentTeachersSeeTheSameProblems() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        String first = loggedIn(Role.TEACHER).get("/taxonomy?node=" + topic.value()).body();
        String second = loggedIn(Role.TEACHER).get("/taxonomy?node=" + topic.value()).body();

        assertThat(first)
                .as("библиотека общая: по владельцу она не фильтруется")
                .contains("№ " + problem.value());
        assertThat(second).contains("№ " + problem.value());
    }

    /** Задачу, заведённую под Администратором, читает и Учитель. */
    @Test
    void theProblemItselfOpensForAnyoneLoggedIn() {
        ProblemId problem = library.problem(library.topic());

        Browser.Page page = loggedIn(Role.TEACHER).get("/problems/" + problem.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Задача № " + problem.value());
    }

    /**
     * Перестройка дерева ({@code rubricator-restructure}) двигает разметку
     * от имени Администратора, и владельца она не принимает (ADR-0027):
     * Задача, перевешенная на Тему-приёмник, — по-прежнему Задача общей
     * библиотеки, и на новом месте её видит каждый Учитель, а не тот, кто
     * её «двигал». Проверяется снова через двух разных Учителей: фильтр,
     * заведённый по пути переезда, не упал бы, а спрятал Задачу от всех,
     * кроме одного.
     */
    @Test
    void aProblemRehomedByRestructuringIsSeenByEveryTeacher() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);
        TaxonomyNodeId receiver = library.topic();

        Browser.Page done = loggedIn(Role.ADMINISTRATOR).postForm("/taxonomy", Map.of(
                "name", "Потомок",
                "parentId", String.valueOf(topic.value()),
                "receiver", String.valueOf(receiver.value())));
        assertThat(done.status()).as("углубление с приёмником прошло").isEqualTo(302);

        String first = loggedIn(Role.TEACHER).get("/taxonomy?node=" + receiver.value()).body();
        String second = loggedIn(Role.TEACHER).get("/taxonomy?node=" + receiver.value()).body();

        assertThat(first)
                .as("перевешенная Задача видна на приёмнике любому Учителю")
                .contains("№ " + problem.value());
        assertThat(second).contains("№ " + problem.value());
    }

    /** Снятие с распределением — тот же путь: приёмник виден всем, снятая Тема — никому. */
    @Test
    void aProblemDistributedByRemovalIsSeenByEveryTeacher() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);
        TaxonomyNodeId elsewhere = library.topic();

        Browser.Page done = loggedIn(Role.ADMINISTRATOR).postForm(
                "/taxonomy/" + topic.value() + "/distribution",
                Map.of("destination[" + problem.value() + "]", String.valueOf(elsewhere.value())));
        assertThat(done.status()).as("снятие с распределением прошло").isEqualTo(302);

        String first = loggedIn(Role.TEACHER).get("/taxonomy?node=" + elsewhere.value()).body();
        String second = loggedIn(Role.TEACHER).get("/taxonomy?node=" + elsewhere.value()).body();

        assertThat(first).contains("№ " + problem.value());
        assertThat(second).contains("№ " + problem.value());
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
