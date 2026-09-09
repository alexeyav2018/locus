package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Требование «Вид узла определяется наличием потомков, а не хранится».
 *
 * Здесь проверяется сама вычислимость: узел без потомков — Тема, узел
 * с потомками — Раздел, и никакого третьего состояния, которое пришлось бы
 * поддерживать в согласии с потомками, у узла нет.
 */
class TaxonomyNodeTest {

    private static final TaxonomyNodeId ID = new TaxonomyNodeId(1);
    private static final TaxonomyNodeId PARENT = new TaxonomyNodeId(2);

    /** Сценарий «Новый узел — Тема». */
    @Test
    void nodeWithoutChildrenIsATopic() {
        TaxonomyNode node = new TaxonomyNode(ID, "Квадратные уравнения", PARENT, 0);

        assertThat(node.isTopic()).isTrue();
        assertThat(node.isSection()).isFalse();
    }

    @Test
    void nodeWithChildrenIsASection() {
        TaxonomyNode node = new TaxonomyNode(ID, "Алгебра", null, 3);

        assertThat(node.isSection()).isTrue();
        assertThat(node.isTopic()).isFalse();
    }

    @Test
    void nodeWithoutParentIsARoot() {
        TaxonomyNode root = new TaxonomyNode(ID, "Алгебра", null, 0);

        assertThat(root.isRoot()).isTrue();
        assertThat(root.parentNode()).isEmpty();
    }

    @Test
    void nodeWithParentKnowsIt() {
        TaxonomyNode node = new TaxonomyNode(ID, "Уравнения", PARENT, 0);

        assertThat(node.isRoot()).isFalse();
        assertThat(node.parentNode()).isEqualTo(Optional.of(PARENT));
    }

    @Test
    void emptyNameIsNotANode() {
        assertThatThrownBy(() -> new TaxonomyNode(ID, "   ", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
    }
}
