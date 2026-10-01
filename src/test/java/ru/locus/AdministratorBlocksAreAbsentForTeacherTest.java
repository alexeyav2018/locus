package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Сценарий «Недоступное действие не появляется» (interface-navigation):
 * свёрнутый блок всё равно лежит в разметке, поэтому Учитель не должен
 * получать блоки Администратора вовсе — ни раскрытыми, ни свёрнутыми.
 * Раскрытие прав не даёт и не отнимает: роль решает сервер.
 */
class AdministratorBlocksAreAbsentForTeacherTest extends IntegrationTest {

    private static final String[] ADMINISTRATOR_ONLY = {
            "Правка узла", "Завести корневой узел", "Новый потомок", "Изменить имя", "Удаление",
            "Новый Метод", "Новая Характеристика", "Завести Метод", "Завести Характеристику",
            "Править Задачу", "Удалить Задачу", "Править материал", "Удалить материал",
            "Завести Задачу", "Завести материал"};

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    @Test
    void teacherGetsNoAdministratorBlocksOnLibraryScreens() {
        TaxonomyNodeId topic = library.topic();
        var problem = library.problem(topic);
        var material = library.material(topic, TestLibrary.unique("Материал"));
        var method = library.method();

        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        Browser teacher = new Browser(port);
        teacher.logIn(account.login(), account.password());

        for (String address : new String[] {
                "/taxonomy?node=" + topic.value(),
                "/problems/" + problem.value(),
                "/theory/" + material.value(),
                "/dictionaries?method=" + method.value()}) {
            String body = teacher.get(address).body();
            assertThat(body).as("страница %s открыта", address).contains("topbar");
            for (String blocked : ADMINISTRATOR_ONLY) {
                assertThat(body).as("на %s у Учителя нет «%s»", address, blocked).doesNotContain(blocked);
            }
        }
    }

    @Test
    void administratorGetsThemAsFoldedBlocks() {
        TaxonomyNodeId topic = library.topic();
        TestAccounts.Account account = accounts.settled(Role.ADMINISTRATOR);
        Browser administrator = new Browser(port);
        administrator.logIn(account.login(), account.password());

        String body = administrator.get("/taxonomy?node=" + topic.value()).body();

        assertThat(body).contains("Изменить имя").contains("Новый потомок").contains("Удаление");
        assertThat(body).as("ничего не раскрыто без отказа").doesNotContain("open=\"open\"");
    }
}
