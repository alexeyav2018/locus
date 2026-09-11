package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Требование «Ведёт дерево только Администратор, читают все вошедшие».
 *
 * Отказ проверяется обращением по прямому адресу, а не отсутствием кнопки
 * в разметке: доступность операции определяется правами, а не тем, показана
 * ли на неё форма.
 *
 * Узлы заводятся через репозиторий: тесту нужно подготовить обстановку,
 * а не проверить права на её подготовку.
 */
class TaxonomyAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TaxonomyRepository nodes;

    /** Сценарий «Учитель читает дерево». */
    @Test
    void teacherSeesTheWholeTree() {
        String root = unique("Алгебра");
        TaxonomyNodeId rootId = nodes.create(root, null);
        String child = unique("Уравнения");
        nodes.create(child, rootId);

        Browser.Page page = loggedIn(Role.TEACHER).get("/taxonomy");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains(root).contains(child);
    }

    /** Сценарий «Учитель пытается изменить дерево». */
    @Test
    void teacherIsRefusedEveryEditingOperation() {
        String rootName = unique("Алгебра");
        TaxonomyNodeId root = nodes.create(rootName, null);
        TaxonomyNodeId child = nodes.create(unique("Уравнения"), root);
        Browser teacher = loggedIn(Role.TEACHER);

        assertThat(teacher.postForm("/taxonomy", Map.of("name", unique("Геометрия"))).status())
                .as("создание узла")
                .isEqualTo(403);
        assertThat(teacher.postForm("/taxonomy/" + root.value() + "/name",
                Map.of("name", "Переименовано учителем")).status())
                .as("переименование")
                .isEqualTo(403);
        assertThat(teacher.postForm("/taxonomy/" + child.value() + "/parent", Map.of("parentId", "")).status())
                .as("перемещение")
                .isEqualTo(403);
        assertThat(teacher.postForm("/taxonomy/" + child.value() + "/deletion", Map.of()).status())
                .as("удаление")
                .isEqualTo(403);
        assertThat(teacher.postForm("/taxonomy",
                Map.of("name", "Потомок", "parentId", String.valueOf(child.value()), "receiver", "created")).status())
                .as("углубление с Темой-приёмником")
                .isEqualTo(403);
        assertThat(teacher.postForm("/taxonomy/" + child.value() + "/distribution", Map.of()).status())
                .as("снятие с распределением")
                .isEqualTo(403);

        assertThat(nodes.findById(root).orElseThrow().name())
                .as("дерево осталось прежним")
                .isEqualTo(rootName);
        assertThat(nodes.findChildren(root)).extracting(TaxonomyNode::id).containsExactly(child);
    }

    /** Сценарий «Два учителя видят одно дерево». */
    @Test
    void twoTeachersSeeTheSameTree() {
        String name = unique("Алгебра");
        nodes.create(name, null);

        Browser.Page first = loggedIn(Role.TEACHER).get("/taxonomy");
        Browser.Page second = loggedIn(Role.TEACHER).get("/taxonomy");

        assertThat(first.body())
                .as("библиотека общая: по владельцу она не фильтруется")
                .contains(name);
        assertThat(second.body()).contains(name);
    }

    /** Сценарий «Невошедший к дереву не допускается». */
    @Test
    void withoutLoginTheTreeIsNotShown() {
        String name = unique("Алгебра");
        nodes.create(name, null);

        Browser.Page page = new Browser(port).get("/taxonomy");

        assertThat(page.redirectsTo("/login")).as("обращение приводит к форме входа").isTrue();
        assertThat(page.body()).doesNotContain(name);
    }

    @Test
    void administratorEditsTheTree() {
        Browser administrator = loggedIn(Role.ADMINISTRATOR);
        String name = unique("Алгебра");

        Browser.Page created = administrator.postForm("/taxonomy", Map.of("name", name));

        assertThat(created.status()).as("операция выполнена, ответ — переадресация").isEqualTo(302);
        assertThat(nodes.findRoots()).extracting(TaxonomyNode::name).contains(name);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
