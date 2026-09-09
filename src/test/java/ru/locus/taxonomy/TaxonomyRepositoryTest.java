package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;

/**
 * Хранение дерева: узел читается обратно, поддерево обходится целиком,
 * а уникальность имён среди братьев держит схема.
 *
 * Проверка идёт на настоящей базе в контейнере — подмены репозитория нет:
 * половина проверяемого здесь живёт в SQL и в индексах, а не в Java.
 *
 * Тесты идут на одной базе, поэтому имя каждого узла своё: имена корней
 * уникальны на всю систему.
 */
class TaxonomyRepositoryTest extends IntegrationTest {

    @Autowired
    private TaxonomyRepository nodes;

    @Test
    void createdRootIsReadBackAsARootWithoutParent() {
        TaxonomyNodeId id = nodes.create(unique("Алгебра"), null);

        TaxonomyNode found = nodes.findById(id).orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.isRoot()).isTrue();
        assertThat(found.childCount()).isZero();
        assertThat(nodes.findRoots())
                .as("корни читаются отдельным методом с явным is null")
                .extracting(TaxonomyNode::id)
                .contains(id);
    }

    @Test
    void childKnowsItsParentAndDoesNotAppearAmongRoots() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", root);

        assertThat(nodes.findById(child).orElseThrow().parentNode()).contains(root);
        assertThat(nodes.findChildren(root)).extracting(TaxonomyNode::id).containsExactly(child);
        assertThat(nodes.findRoots()).extracting(TaxonomyNode::id).doesNotContain(child);
    }

    @Test
    void childrenComeInAlphabeticalOrder() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        nodes.create("cc", root);
        nodes.create("aa", root);
        nodes.create("bb", root);

        assertThat(nodes.findChildren(root)).extracting(TaxonomyNode::name).containsExactly("aa", "bb", "cc");
    }

    @Test
    void childCountIsReadAlongWithTheNodeAndMakesItsKind() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        assertThat(nodes.findById(root).orElseThrow().isTopic())
                .as("узел без потомков — Тема")
                .isTrue();

        nodes.create("Уравнения", root);

        assertThat(nodes.findById(root).orElseThrow().isSection())
                .as("тот же узел с потомком — Раздел, ничего не переписывалось")
                .isTrue();
        assertThat(nodes.countChildren(root)).isEqualTo(1);
    }

    @Test
    void renamedNodeKeepsItsPlaceAndItsChildren() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", root);

        nodes.rename(child, "Неравенства");

        TaxonomyNode renamed = nodes.findById(child).orElseThrow();
        assertThat(renamed.name()).isEqualTo("Неравенства");
        assertThat(renamed.parentNode()).contains(root);
    }

    @Test
    void changedParentMovesTheNodeAndBackToTheRoot() {
        TaxonomyNodeId first = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId second = nodes.create(unique("Геометрия"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", first);

        nodes.changeParent(child, second);
        assertThat(nodes.findById(child).orElseThrow().parentNode()).contains(second);
        assertThat(nodes.findChildren(first)).isEmpty();

        nodes.changeParent(child, null);
        assertThat(nodes.findById(child).orElseThrow().isRoot())
                .as("null означает подъём в корень")
                .isTrue();
    }

    @Test
    void deletedNodeDisappears() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", root);

        nodes.delete(child);

        assertThat(nodes.findById(child)).isEmpty();
        assertThat(nodes.findById(root)).isPresent();
    }

    /**
     * Каскада по внешнему ключу нет намеренно: попытка снести узел
     * с потомками падает, даже если проверка в сервисе однажды окажется
     * обойдённой.
     */
    @Test
    void deletingANodeWithChildrenIsRefusedByTheSchemaItself() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId child = nodes.create("Уравнения", root);

        assertThatThrownBy(() -> nodes.delete(root)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(nodes.findById(root)).isPresent();
        assertThat(nodes.findById(child)).isPresent();
    }

    /** Сценарий «Поддерево на четырёх уровнях». */
    @Test
    void subtreeReturnsEveryDescendantAndTheNodeItself() {
        TaxonomyNodeId first = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId second = nodes.create("Уравнения", first);
        TaxonomyNodeId third = nodes.create("Квадратные", second);
        TaxonomyNodeId fourth = nodes.create("Неполные", third);

        assertThat(nodes.findSubtree(first))
                .extracting(TaxonomyNode::id)
                .containsExactlyInAnyOrder(first, second, third, fourth);
    }

    /** Сценарий «Поддерево листа». */
    @Test
    void subtreeOfALeafIsTheLeafAlone() {
        TaxonomyNodeId leaf = nodes.create(unique("Алгебра"), null);

        assertThat(nodes.findSubtree(leaf)).extracting(TaxonomyNode::id).containsExactly(leaf);
    }

    /** Сценарий «Соседняя ветка не попадает в результат». */
    @Test
    void siblingBranchStaysOutOfTheSubtree() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        TaxonomyNodeId asked = nodes.create("Уравнения", root);
        TaxonomyNodeId sibling = nodes.create("Неравенства", root);
        TaxonomyNodeId siblingChild = nodes.create("Линейные", sibling);

        assertThat(nodes.findSubtree(asked))
                .extracting(TaxonomyNode::id)
                .containsExactly(asked)
                .doesNotContain(sibling, siblingChild);
    }

    /** Задача 1.2: уникальность имени среди братьев держит индекс. */
    @Test
    void sameNameAmongSiblingsIsRefusedBySchema() {
        TaxonomyNodeId root = nodes.create(unique("Алгебра"), null);
        nodes.create("Уравнения", root);

        assertThatThrownBy(() -> nodes.create("Уравнения", root))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Задача 1.3: у корней своя уникальность, частичным индексом. Обычный
     * индекс их не покрывает — NULL не равен NULL.
     */
    @Test
    void sameNameAmongRootsIsRefusedBySchema() {
        String name = unique("Алгебра");
        nodes.create(name, null);

        assertThatThrownBy(() -> nodes.create(name, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Сценарий «То же имя под другим родителем». */
    @Test
    void sameNameUnderAnotherParentIsAllowed() {
        TaxonomyNodeId triangles = nodes.create(unique("Треугольники"), null);
        TaxonomyNodeId circles = nodes.create(unique("Окружности"), null);

        nodes.create("Признаки подобия", triangles);
        nodes.create("Признаки подобия", circles);

        assertThat(nodes.findChildren(triangles)).extracting(TaxonomyNode::name).containsExactly("Признаки подобия");
        assertThat(nodes.findChildren(circles)).extracting(TaxonomyNode::name).containsExactly("Признаки подобия");
    }

    @Test
    void unknownIdentifierIsNotFound() {
        List<TaxonomyNode> roots = nodes.findRoots();
        long free = roots.stream().mapToLong(node -> node.id().value()).max().orElse(0) + 1_000_000;

        assertThat(nodes.findById(new TaxonomyNodeId(free))).isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
