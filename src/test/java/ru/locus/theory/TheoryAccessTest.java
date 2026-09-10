package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * Требование «Материалы ведёт Администратор, читают все вошедшие».
 *
 * Отказ проверяется обращением по прямому адресу, а не отсутствием кнопки
 * в разметке: доступность операции определяется правами, а не тем, показана
 * ли на неё форма.
 *
 * Материалы заводятся через репозиторий: тесту нужно подготовить обстановку,
 * а не проверить права на её подготовку.
 */
class TheoryAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TheoryMaterialRepository materials;

    /** Сценарий «Учитель читает материалы». */
    @Test
    void teacherOpensAMaterialWithItsNodeAndContent() {
        TaxonomyNodeId node = library.topic();
        TheoryMaterialId material = library.material(node, "Конспект по тождествам");

        Browser.Page page = loggedIn(Role.TEACHER).get("/theory/" + material.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Конспект по тождествам")
                .contains("Узел рубрикатора")
                .as("действий правки Учителю не предлагают")
                .doesNotContain("Удалить материал");
    }

    /** Сценарий «Учитель пытается завести материал». */
    @Test
    void teacherIsRefusedEveryEditingOperation() {
        TaxonomyNodeId node = library.topic();
        TheoryMaterialId material = library.material(node, "Прежний конспект");
        Browser teacher = loggedIn(Role.TEACHER);

        assertThat(teacher.postMultipart("/theory",
                Map.of("title", "Чужой конспект", "node", String.valueOf(node.value())),
                Map.of("file", TestLibrary.pdf())).status())
                .as("заведение материала")
                .isEqualTo(403);
        assertThat(teacher.postForm("/theory/" + material.value(),
                Map.of("title", "Переименованный", "node", String.valueOf(node.value()))).status())
                .as("правка названия и узла")
                .isEqualTo(403);
        assertThat(teacher.postMultipart("/theory/" + material.value() + "/content",
                Map.of("link", ""), Map.of("file", TestLibrary.pdf())).status())
                .as("замена содержимого")
                .isEqualTo(403);
        assertThat(teacher.postForm("/theory/" + material.value() + "/deletion", Map.of()).status())
                .as("удаление")
                .isEqualTo(403);

        List<TheoryMaterial> onNode = materials.findByNodes(List.of(node));
        assertThat(onNode).as("состав материалов остался прежним")
                .extracting(TheoryMaterial::title)
                .containsExactly("Прежний конспект");
    }

    /** Сценарий «Невошедшего к материалам не допускают». */
    @Test
    void withoutLoginNeitherTheMaterialNorItsFileIsShown() {
        TheoryMaterialId material = library.material(library.topic(), "Закрытый конспект");

        Browser browser = new Browser(port);

        assertThat(browser.get("/theory/" + material.value()).redirectsTo("/login"))
                .as("обращение к материалу приводит к форме входа")
                .isTrue();
        Browser.Page file = browser.get("/theory/" + material.value() + "/file");
        assertThat(file.redirectsTo("/login"))
                .as("подписанная ссылка невошедшему не выдаётся")
                .isTrue();
    }

    /** Администратор заводит материал формой — и файлом, и ссылкой. */
    @Test
    void administratorCreatesMaterialsThroughTheForm() {
        TaxonomyNodeId node = library.topic();
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        Browser.Page withFile = administrator.postMultipart("/theory",
                Map.of("title", "Конспект файлом", "node", String.valueOf(node.value()), "link", ""),
                Map.of("file", TestLibrary.pdf()));
        Browser.Page withLink = administrator.postMultipart("/theory",
                Map.of("title", "Конспект ссылкой", "node", String.valueOf(node.value()),
                        "link", "https://example.org/konspekt"),
                Map.of());

        assertThat(withFile.status()).as("операция выполнена, ответ — переадресация").isEqualTo(302);
        assertThat(withLink.status()).isEqualTo(302);
        assertThat(materials.findByNodes(List.of(node)))
                .extracting(TheoryMaterial::title)
                .containsExactlyInAnyOrder("Конспект файлом", "Конспект ссылкой");
    }

    /** Приложенный файл читается по выданной подписанной ссылке. */
    @Test
    void theFileIsReachedThroughTheSignedLink() {
        TheoryMaterialId material = library.material(library.topic(), "Конспект с файлом");
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        Browser.Page link = administrator.get("/theory/" + material.value() + "/file");

        assertThat(link.status()).as("переход уводит на подписанную ссылку").isEqualTo(302);
        assertThat(link.location()).as("постоянного адреса у файла нет").contains("/file");
        assertThat(administrator.getBytes(relative(link.location())))
                .as("по выданной ссылке содержимое читается")
                .isNotEmpty();
    }

    private String relative(String location) {
        String base = "http://localhost:" + port;
        return location.startsWith(base) ? location.substring(base.length()) : location;
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
