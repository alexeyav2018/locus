package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Требование «Экран дерева показывает структуру целиком».
 *
 * Экран разделён: слева дерево, справа действия над выбранным узлом.
 * Структура нужна видимой целиком именно потому, что Администратор её
 * перестраивает, — а формы, вставленные между узлами, прячут ровно то,
 * ради чего человек пришёл.
 */
class TaxonomyScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TaxonomyRepository nodes;

    /** Сценарий «Дерево видно целиком». */
    @Test
    void wholeTreeIsShownWithItsNesting() {
        String root = unique("Алгебра");
        TaxonomyNodeId rootId = nodes.create(root, null);
        String child = unique("Уравнения");
        TaxonomyNodeId childId = nodes.create(child, rootId);
        String grandchild = unique("Квадратные");
        nodes.create(grandchild, childId);

        Browser.Page page = loggedIn(Role.ADMINISTRATOR).get("/taxonomy");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .as("показаны все узлы, включая самые нижние")
                .contains(root)
                .contains(child)
                .contains(grandchild);
        assertThat(page.body())
                .as("вложенность видна списками, ветви сворачиваются штатным details")
                .contains("<ul")
                .contains("<details");
        assertThat(page.body())
                .as("разметка собрана на сервере")
                .doesNotContain("th:text");
    }

    /**
     * Узел показывается в дереве один раз — и лист, и узел с потомками.
     *
     * Проверка не праздная: {@code th:replace} выполняется раньше
     * {@code th:if}, поэтому условие показа, написанное на том же теге,
     * теряется молча, и ссылка на узел с потомками выводится дважды —
     * в заголовке ветви и ещё раз под ней. Разметка при этом остаётся
     * валидной, а имена — на месте, так что проверка «все узлы показаны»
     * такого не ловит.
     */
    @Test
    void everyNodeIsShownExactlyOnceInTheTree() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId branch = nodes.create("Уравнения", root);
        TaxonomyNodeId leaf = nodes.create("Квадратные", branch);

        String body = loggedIn(Role.TEACHER).get("/taxonomy").body();

        assertThat(count(body, link(root))).as("корень с потомками").isEqualTo(1);
        assertThat(count(body, link(branch))).as("узел с потомками").isEqualTo(1);
        assertThat(count(body, link(leaf))).as("лист").isEqualTo(1);
    }

    /** Сценарий «Братья по алфавиту». */
    @Test
    void siblingsAreShownInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId third = nodes.create("cc-" + mark, root);
        TaxonomyNodeId first = nodes.create("aa-" + mark, root);
        TaxonomyNodeId second = nodes.create("bb-" + mark, root);

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy").body();

        assertThat(body.indexOf(anchor(first)))
                .as("порядок показа не совпадает с порядком заведения — он алфавитный")
                .isLessThan(body.indexOf(anchor(second)));
        assertThat(body.indexOf(anchor(second))).isLessThan(body.indexOf(anchor(third)));
    }

    /** Сценарий «Действия относятся к выбранному узлу». */
    @Test
    void actionsBelongToTheSelectedNodeAndAreNotInTheTree() {
        String root = unique("Алгебра");
        TaxonomyNodeId rootId = nodes.create(root, null);
        String child = unique("Уравнения");
        TaxonomyNodeId childId = nodes.create(child, rootId);

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + childId.value()).body();

        assertThat(body)
                .as("справа показан путь выбранного узла от корня")
                .contains(root + " / " + child);
        assertThat(count(body, "Переименовать"))
                .as("действие одно на странице, а не по одному у каждого узла — в дереве их нет")
                .isEqualTo(1);
        assertThat(count(body, "Добавить потомка")).isEqualTo(1);
        assertThat(count(body, "Удалить")).isEqualTo(1);
        assertThat(count(body, "class=\"selected\""))
                .as("выбранный узел в дереве подсвечен, и он один")
                .isEqualTo(1);
    }

    /**
     * Сценарий «На пустой Теме перестройки не предлагают»: формы прежние —
     * ни выбора Темы-приёмника, ни снятия с распределением. Обратная сторона
     * — обе формы на Теме с Задачами — проверяется там, где Задачи известны:
     * {@code RestructureScreenTest} в пакете Задач.
     */
    @Test
    void emptyTopicIsOfferedThePlainFormsWithoutReceiverOrDistribution() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId topic = nodes.create("Уравнения", root);

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + topic.value()).body();

        assertThat(body)
                .as("обычные создание потомка и удаление на месте")
                .contains("Добавить потомка")
                .contains("action=\"/taxonomy/" + topic.value() + "/deletion\"");
        assertThat(body)
                .as("ни приёмника, ни распределения не запрашивается")
                .doesNotContain("Тема-приёмник")
                .doesNotContain("name=\"receiver\"")
                .doesNotContain("Снять с распределением")
                .doesNotContain("/distribution\"");
    }

    /** Сценарий «Пока узел не выбран». */
    @Test
    void withoutASelectedNodeOnlyTheRootFormIsOffered() {
        String name = unique("Алгебра");
        nodes.create(name, null);

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy").body();

        assertThat(body).as("дерево показано целиком").contains(name);
        assertThat(body)
                .as("действий над узлом не предлагается")
                .doesNotContain("Переименовать")
                .doesNotContain("Перенести")
                .doesNotContain("Удалить");
        assertThat(body)
                .as("завести корневой узел всё равно можно")
                .contains("Завести корневой узел");
    }

    /** Сценарий «Выбран узел, которого больше нет». */
    @Test
    void selectingAVanishedNodeStillShowsTheTree() {
        String name = unique("Алгебра");
        TaxonomyNodeId gone = nodes.create(unique("Уравнения"), null);
        nodes.create(name, null);
        nodes.delete(gone);

        Browser.Page page = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + gone.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).as("дерево показывается").contains(name);
        assertThat(page.body()).as("об исчезнувшем узле сообщается").contains("не существует");
        assertThat(page.body()).as("действий над ним не предлагается").doesNotContain("Переименовать");
    }

    /** Сценарий «Возврат к выбранному узлу после операции». */
    @Test
    void afterRenamingThePageComesBackToTheSameNodeSelected() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId deep = nodes.create("Уравнения", nodes.create("Ветвь", root));

        Browser.Page done = loggedIn(Role.ADMINISTRATOR)
                .postForm("/taxonomy/" + deep.value() + "/name", Map.of("name", "Неравенства"));

        assertThat(done.location())
                .as("параметр держит выбор, якорь — место в дереве")
                .endsWith("/taxonomy?node=" + deep.value() + "#node-" + deep.value());
        assertThat(nodes.findById(deep).orElseThrow().name()).isEqualTo("Неравенства");
    }

    @Test
    void afterDeletingThePageComesBackToTheFormerParent() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", root);

        Browser.Page done = loggedIn(Role.ADMINISTRATOR)
                .postForm("/taxonomy/" + child.value() + "/deletion", Map.of());

        assertThat(done.location()).endsWith("/taxonomy?node=" + root.value() + "#node-" + root.value());
        assertThat(nodes.findById(child)).isEmpty();
    }

    /**
     * Задача 4.7: список выбора родителя один на страницу.
     *
     * До разделения панелей форма перемещения повторялась у каждого узла,
     * а внутри неё — список всех узлов дерева: на сотне узлов сто списков
     * по сотне пунктов. Это вторая, тяжёлая причина разделения.
     */
    @Test
    void onlyOneParentSelectIsRenderedNoMatterHowManyNodes() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", root);
        nodes.create("Квадратные", child);
        nodes.create("Неравенства", root);

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + child.value()).body();

        assertThat(count(body, "<select name=\"parentId\""))
                .as("список выбора родителя один, а не по одному на узел")
                .isEqualTo(1);
    }

    /** Сценарий «Учителю операций не предлагают». */
    @Test
    void teacherIsShownTheNodeWithoutAnyAction() {
        String root = unique("Алгебра");
        TaxonomyNodeId rootId = nodes.create(root, null);
        String child = unique("Уравнения");
        TaxonomyNodeId childId = nodes.create(child, rootId);

        String body = loggedIn(Role.TEACHER).get("/taxonomy?node=" + childId.value()).body();

        assertThat(body).as("дерево показано").contains(root).contains(child);
        assertThat(body)
                .as("путь узла от корня Учителю виден — в дереве его не прочитать")
                .contains(root + " / " + child);
        assertThat(body)
                .as("действий правки на экране нет")
                .doesNotContain("Переименовать")
                .doesNotContain("Добавить потомка")
                .doesNotContain("Перенести")
                .doesNotContain("Удалить")
                .doesNotContain("Завести корневой узел");
    }

    /** Задача 4.5: со страницы после входа на дерево можно перейти ссылкой. */
    @Test
    void treeIsReachableFromTheHomePage() {
        String name = unique("Алгебра");
        nodes.create(name, null);
        Browser browser = loggedIn(Role.TEACHER);

        assertThat(browser.get("/").body())
                .as("в навигации есть пункт рубрикатора")
                .contains("href=\"/taxonomy\"");

        assertThat(browser.get("/taxonomy").body()).contains(name);
    }

    /**
     * Задача 4.2: ни одной строки JavaScript. Свёртка ветвей — штатный
     * {@code details}, выбор узла — обычная ссылка, операции — обычные формы
     * с перезагрузкой страницы (ADR-0020).
     */
    @Test
    void noTemplateCarriesAScript() throws IOException {
        try (Stream<Path> templates = Files.walk(Path.of("src/main/resources/templates"))) {
            assertThat(templates.filter(Files::isRegularFile))
                    .allSatisfy(template -> assertThat(Files.readString(template, StandardCharsets.UTF_8))
                            .as("шаблон %s не должен содержать скриптов", template)
                            .doesNotContain("script"));
        }
    }

    private static int count(String body, String fragment) {
        int found = 0;
        for (int at = body.indexOf(fragment); at >= 0; at = body.indexOf(fragment, at + fragment.length())) {
            found++;
        }
        return found;
    }

    private static String link(TaxonomyNodeId id) {
        return "href=\"/taxonomy?node=" + id.value() + "\"";
    }

    private static String anchor(TaxonomyNodeId id) {
        return "id=\"node-" + id.value() + "\"";
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
