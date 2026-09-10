package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.Role;

/**
 * Требование «Материал виден на всём поддереве своего узла».
 *
 * Наследование нигде не хранится: оно вычисляется подъёмом по предкам
 * ({@link TaxonomyService#ancestry}) и запросом по получившемуся набору узлов.
 * Поэтому проверяется именно то, что вычисление даёт: материал предка виден,
 * материал потомка — нет, брат не наследует ничего, а порядок в списке идёт
 * от частного к общему.
 *
 * Обстановка строится через репозитории ({@link TestLibrary}): тесту нужно
 * подготовить дерево с материалами, а не проверить права на его подготовку.
 */
class TheoryInheritanceTest extends IntegrationTest {

    @Autowired
    private TheoryService theory;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TaxonomyService taxonomy;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.TEACHER);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Материал Раздела виден на Теме внутри», с названным источником. */
    @Test
    void materialOfASectionIsSeenOnATopicInsideItAndItsSourceIsNamed() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        library.material(section, "Общий конспект");

        assertThat(theory.materialsOn(topic))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.material().title()).isEqualTo("Общий конспект");
                    assertThat(found.own()).as("материал не свой: он пришёл сверху").isFalse();
                    assertThat(found.source())
                            .as("назван узел, с которого материал пришёл")
                            .isEqualTo(name(section));
                });
    }

    /** Сценарий «Материал виден на четвёртом уровне». */
    @Test
    void materialOfTheRootIsSeenFourLevelsDown() {
        TaxonomyNodeId root = library.topic();
        TaxonomyNodeId second = library.topic(root);
        TaxonomyNodeId third = library.topic(second);
        TaxonomyNodeId fourth = library.topic(third);
        library.material(root, "Конспект корня");

        assertThat(theory.materialsOn(fourth))
                .extracting(node -> node.material().title())
                .containsExactly("Конспект корня");
    }

    /**
     * Сценарии «Свои и унаследованные вместе» и «Порядок своих
     * и унаследованных»: сначала свой, дальше от ближайшего предка к корню.
     */
    @Test
    void ownMaterialComesFirstAndThenAncestorsFromTheNearestToTheRoot() {
        TaxonomyNodeId root = library.topic();
        TaxonomyNodeId middle = library.topic(root);
        TaxonomyNodeId topic = library.topic(middle);
        library.material(root, "Материал корня");
        library.material(middle, "Материал середины");
        library.material(topic, "Свой материал");

        assertThat(theory.materialsOn(topic))
                .extracting(node -> node.material().title())
                .containsExactly("Свой материал", "Материал середины", "Материал корня");
        assertThat(theory.materialsOn(topic))
                .extracting(NodeTheory::own)
                .containsExactly(true, false, false);
    }

    /** Внутри одного узла порядок — по названию. */
    @Test
    void materialsOfOneNodeGoByTitle() {
        TaxonomyNodeId topic = library.topic();
        library.material(topic, "Ясность изложения");
        library.material(topic, "Алгебраические преобразования");

        assertThat(theory.materialsOn(topic))
                .extracting(node -> node.material().title())
                .containsExactly("Алгебраические преобразования", "Ясность изложения");
    }

    /** Сценарий «Материал Темы не виден на Разделе»: наследование идёт только вниз. */
    @Test
    void materialOfATopicIsNotSeenOnTheSectionAboveIt() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        library.material(topic, "Узкий материал листа");

        assertThat(theory.materialsOn(section)).isEmpty();
    }

    /** Сценарий «Соседняя ветка не наследует». */
    @Test
    void aSiblingInheritsNothing() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        TaxonomyNodeId sibling = library.topic(section);
        library.material(topic, "Материал соседа");

        assertThat(theory.materialsOn(sibling)).isEmpty();
    }

    /**
     * Сценарий «Узел переехал в дереве»: наследование вычисляется, поэтому
     * перенос узла меняет видимость сам собой — пересчитывать нечего.
     */
    @Test
    void movingANodeMovesWhatItsSubtreeInherits() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        TaxonomyNodeId moved = library.topic(from);
        TaxonomyNodeId below = library.topic(moved);
        library.material(from, "Материал прежнего родителя");

        assertThat(theory.materialsOn(below))
                .as("на прежнем месте материал родителя наследовался")
                .hasSize(1);

        LoggedIn.as(Role.ADMINISTRATOR);
        taxonomy.move(moved, to);
        LoggedIn.as(Role.TEACHER);

        assertThat(theory.materialsOn(below))
                .as("на новом месте прежний материал больше не наследуется")
                .isEmpty();
    }

    /** Материалов нет — список пуст, и это не ошибка. */
    @Test
    void anEmptyNodeGivesAnEmptyList() {
        assertThat(theory.materialsOn(library.topic())).isEmpty();
    }

    /** Узла нет — отказ, а не правдоподобный пустой список. */
    @Test
    void aNodeThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> theory.materialsOn(new TaxonomyNodeId(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Узла рубрикатора");
    }

    private String name(TaxonomyNodeId node) {
        return taxonomy.node(node).name();
    }
}
