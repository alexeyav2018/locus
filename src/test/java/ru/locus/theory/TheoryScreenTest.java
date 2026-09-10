package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyRepository;
import ru.locus.user.Role;

/**
 * Экраны Теоретического материала: список в правой части экрана дерева, форма
 * заведения и страница просмотра.
 *
 * Список показывается на <b>любом</b> узле, а не только на Теме: материал
 * ложится и на Раздел (ADR-0032), и объяснение вместо списка — то, что нужно
 * Задачам, — здесь было бы неправдой.
 *
 * Сравнивается видимый человеку текст, а не разметка целиком: сравнение всей
 * страницы ломается от переноса строки и от лишнего пробела, и чинить его
 * приходится на каждой правке шаблона (antipatterns.md, «Сравнение разметки
 * страницы целиком»).
 */
class TheoryScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TaxonomyRepository nodes;

    /** Сценарий «Материал на Теме»: свой материал показан своим. */
    @Test
    void chosenTopicShowsItsOwnMaterials() {
        TaxonomyNodeId topic = library.topic();
        library.material(topic, "Тождества приведения");

        String page = administrator().get("/taxonomy?node=" + topic.value()).body();

        assertThat(page)
                .contains("Теоретические материалы")
                .contains("Тождества приведения")
                .as("вид содержимого назван")
                .contains("файл")
                .as("свой материал источником не помечается")
                .doesNotContain("унаследован с узла")
                .as("действие заведения предлагается прямо здесь")
                .contains("Завести материал");
    }

    /** Сценарий «Материал на Разделе»: список есть и у узла с потомками. */
    @Test
    void chosenSectionShowsItsMaterialsToo() {
        TaxonomyNodeId section = nodes.create(TestLibrary.unique("Раздел"), null);
        nodes.create("Потомок", section);
        library.linkedMaterial(section, "Общий конспект");

        String page = administrator().get("/taxonomy?node=" + section.value()).body();

        assertThat(page)
                .contains("Общий конспект")
                .as("материал-ссылка назван ссылкой")
                .contains("ссылка")
                .as("отказа «только листья» у теории нет")
                .doesNotContain("Материалов на этом узле нет.");
    }

    /** Сценарий «Материал Раздела виден на Теме внутри»: с именем источника. */
    @Test
    void inheritedMaterialNamesTheNodeItCameFrom() {
        String sectionName = TestLibrary.unique("Тригонометрия");
        TaxonomyNodeId section = nodes.create(sectionName, null);
        TaxonomyNodeId topic = nodes.create("Тождества", section);
        library.material(section, "Конспект Раздела");
        library.material(topic, "Разбор Темы");

        String page = administrator().get("/taxonomy?node=" + topic.value()).body();

        assertThat(page)
                .contains("Разбор Темы")
                .contains("Конспект Раздела")
                .as("унаследованный материал назван вместе с узлом-источником")
                .contains("унаследован с узла: " + sectionName);
        assertThat(page.indexOf("Разбор Темы"))
                .as("свои материалы идут первыми")
                .isLessThan(page.indexOf("Конспект Раздела"));
    }

    /** Сценарий «Узел без материалов»: сказано прямо, а не пустым местом. */
    @Test
    void nodeWithoutMaterialsSaysSoAndStillOffersToCreateOne() {
        TaxonomyNodeId topic = library.topic();

        String page = administrator().get("/taxonomy?node=" + topic.value()).body();

        assertThat(page).contains("Материалов на этом узле нет.").contains("Завести материал");
    }

    /**
     * Сценарий «Учитель читает материалы»: список тот же, действий не предлагают.
     *
     * Отсутствие ссылки правами не является — права проверяет сервис
     * ({@code TheoryAccessTest}). Здесь проверяется, что Учителю не показывают
     * того, чего он всё равно не сможет сделать.
     */
    @Test
    void teacherSeesTheListButNotTheActions() {
        TaxonomyNodeId topic = library.topic();
        library.material(topic, "Конспект для Учителя");

        String page = loggedIn(Role.TEACHER).get("/taxonomy?node=" + topic.value()).body();

        assertThat(page)
                .contains("Конспект для Учителя")
                .as("заведение Учителю не предлагается")
                .doesNotContain("Завести материал");
    }

    /** Форма заведения приходит с узлом, с которого на неё пришли. */
    @Test
    void creationFormComesWithTheChosenNodeSelected() {
        TaxonomyNodeId node = library.topic();

        String form = administrator().get("/theory/new?node=" + node.value()).body();

        assertThat(form)
                .contains("Новый Теоретический материал")
                .contains("selected=\"selected\"")
                .contains("value=\"" + node.value() + "\"");
    }

    /** Страница материала: узел, вид содержимого и путь к нему. */
    @Test
    void materialPageShowsItsNodeAndTheWayToTheContent() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId material = library.material(topic, "Тождества приведения");

        String page = administrator().get("/theory/" + material.value()).body();

        assertThat(page)
                .contains("Тождества приведения")
                .as("узел назван путём от корня")
                .contains("Узел рубрикатора")
                .as("файл открывается через выдачу подписанной ссылки")
                .contains("/theory/" + material.value() + "/file")
                .contains("Править материал");
    }

    /** Правка идёт той же формой, заполненной существующим материалом. */
    @Test
    void editingFormComesFilledWithTheMaterial() {
        TheoryMaterialId material = library.material(library.topic(), "Конспект");

        String form = administrator().get("/theory/" + material.value() + "/edit").body();

        assertThat(form)
                .contains("Конспект")
                .contains("Сохранить")
                .as("замена содержимого предлагается отдельной формой")
                .contains("Заменить содержимое");
    }

    /** Удаление возвращает на узел, где материал лежал. */
    @Test
    void deletingAMaterialReturnsToItsNode() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId material = library.material(topic, "Ненужное");

        Browser.Page deleted = administrator()
                .postForm("/theory/" + material.value() + "/deletion", java.util.Map.of());

        assertThat(deleted.redirectsTo("#node-" + topic.value()))
                .as("место, где работал Администратор, не теряется")
                .isTrue();
    }

    private Browser administrator() {
        return loggedIn(Role.ADMINISTRATOR);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
