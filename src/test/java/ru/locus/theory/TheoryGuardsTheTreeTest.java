package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.taxonomy.NodeNotEmptyException;
import ru.locus.taxonomy.TaxonomyNode;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.taxonomy.TopicDistribution;
import ru.locus.user.Role;

/**
 * Требования рубрикатора «Удаляется только пустой узел» и «Создание узла» —
 * та их часть, которая касается Теоретических материалов.
 *
 * Долг записан в спеке дерева и погашен здесь: проверка отсутствия материалов
 * на удаляемом узле теперь <b>действует</b>. Но действует она не так, как
 * проверка Задач, и в этом весь смысл теста — теория отвечает дереву
 * на два вопроса по-разному:
 *
 * <ul>
 *   <li>удалению узла свои материалы мешают — с узлом они исчезли бы;
 *   <li>углублению узла материалы <b>не</b> мешают — материал живёт на любом
 *       узле и наследуется вниз, поэтому новый потомок его не теряет
 *       (ADR-0032);
 *   <li>унаследованные материалы не мешают ничему — они лежат не здесь.
 * </ul>
 *
 * Проверяется через {@link TaxonomyService}, а не через {@link TheoryOnNode}:
 * важно, что дерево отказывает, а не что кто-то умеет посчитать материалы.
 */
class TheoryGuardsTheTreeTest extends IntegrationTest {

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private TheoryService theory;

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

    /** Сценарий «Удаление узла с Теоретическими материалами». */
    @Test
    void nodeCarryingItsOwnMaterialsIsNotDeleted() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId material = library.material(topic, "Формулы приведения");

        assertThatThrownBy(() -> taxonomy.delete(topic))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("Теоретические материалы");

        assertThat(taxonomy.node(topic)).as("узел на месте").isNotNull();
        assertThat(theory.material(material)).as("его материалы на месте").isNotNull();
    }

    /**
     * Сценарий «Снятие Темы, несущей свои материалы».
     *
     * Снятие с распределением материалам не помогает: распределять их некуда,
     * а исчезать вместе с узлом они не должны. Отказ обязан назвать выход —
     * перенос материала на другой узел, который есть обычная его правка
     * и ничем не обусловлен: заморозки у теории нет (ADR-0033).
     */
    @Test
    void nodeCarryingItsOwnMaterialsIsNotDeletedWithDistributionEither() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId material = library.material(topic, "Формулы приведения");

        assertThatThrownBy(() -> taxonomy.deleteWithDistribution(topic, TopicDistribution.NOTHING))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("Теоретические материалы")
                .as("выход назван: снять или перенести обычной правкой")
                .hasMessageContaining("перенесите")
                .hasMessageContaining("обычной правкой");

        assertThat(taxonomy.node(topic)).as("узел на месте").isNotNull();
        assertThat(theory.material(material)).as("его материалы на месте").isNotNull();
    }

    /**
     * Тот же отказ на Разделе: правило «только листья» на теорию
     * не распространяется, и держит узел не его вид, а лежащий на нём
     * материал.
     */
    @Test
    void aSectionIsHeldByItsMaterialsJustTheSame() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId child = library.topic(section);
        library.material(section, "Тригонометрия: общее");

        taxonomy.delete(child);

        assertThatThrownBy(() -> taxonomy.delete(section))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("Теоретические материалы");
    }

    /**
     * Сценарий «Удаление узла, наследующего чужие материалы».
     *
     * Считай {@link TheoryOnNode} унаследованное — и ни одну Тему внутри
     * Раздела с материалом нельзя было бы удалить, причём отказ ссылался бы
     * на материал, которого на узле нет.
     */
    @Test
    void nodeInheritingSomeoneElsesMaterialsIsDeleted() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        TheoryMaterialId inherited = library.material(section, "Формулы приведения");

        assertThat(theory.materialsOn(topic))
                .as("материал предка на Теме действительно виден")
                .extracting(NodeTheory::own)
                .containsExactly(false);

        assertThatCode(() -> taxonomy.delete(topic))
                .as("унаследованное узел не держит — оно лежит не здесь")
                .doesNotThrowAnyException();
        assertThat(theory.material(inherited))
                .as("материал вышестоящего узла остался на месте")
                .isNotNull();
    }

    /** Сценарий «Удаление узла, освободившегося от материалов». */
    @Test
    void nodeFreedFromItsLastMaterialIsDeleted() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId material = library.material(topic, "Формулы приведения");

        theory.delete(material);

        assertThatCode(() -> taxonomy.delete(topic))
                .as("условие перестало выполняться — удаление разрешено")
                .doesNotThrowAnyException();
    }

    /**
     * Сценарий «Потомок у Темы с Теоретическими материалами».
     *
     * Здесь два вопроса дерева расходятся впервые. Задача при углублении
     * пропала бы из дерева молча — она висит только на Теме; материал
     * остаётся законным содержимым узла, ставшего Разделом, и продолжает
     * наследоваться вниз, теперь уже и новому потомку.
     */
    @Test
    void topicCarryingMaterialsIsDeepenedAllTheSame() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId material = library.material(topic, "Формулы приведения");

        TaxonomyNodeId child = taxonomy.create("Потомок", topic);

        TaxonomyNode wasTopic = taxonomy.node(topic);
        assertThat(wasTopic.isSection()).as("вид прежней Темы — Раздел").isTrue();
        assertThat(theory.material(material).node())
                .as("материал остался на прежнем узле")
                .isEqualTo(topic);
        assertThat(theory.materialsOn(child))
                .as("и новому потомку он наследуется")
                .extracting(item -> item.material().id())
                .containsExactly(material);
    }
}
