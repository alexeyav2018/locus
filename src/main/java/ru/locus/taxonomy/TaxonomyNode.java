package ru.locus.taxonomy;

import java.util.Optional;

/**
 * Узел рубрикатора.
 *
 * Раздел и Тема — не два вида записи, а два состояния одной: есть потомки —
 * Раздел, нет потомков — Тема (glossary.md). Отдельных типов {@code Section}
 * и {@code Topic} поэтому нет: они требовали бы перекладывания записи из
 * одного класса в другой при появлении первого потомка.
 *
 * Вид узла здесь <b>вычисляется</b> из числа потомков, прочитанного вместе
 * с узлом, и нигде не хранится. Хранимый признак был бы вторым источником
 * правды о том, лист ли узел, и разошёлся бы с первым молча — а на «узел —
 * лист» опираются привязка Задачи (инвариант 1) и отметка Владения
 * (инвариант 3).
 *
 * @param parent     родитель узла; {@code null} означает корневой узел
 * @param childCount число прямых потомков на момент чтения
 */
public record TaxonomyNode(TaxonomyNodeId id, String name, TaxonomyNodeId parent, int childCount) {

    public TaxonomyNode {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя узла рубрикатора не может быть пустым");
        }
        if (childCount < 0) {
            throw new IllegalArgumentException("Число потомков не может быть отрицательным: " + childCount);
        }
    }

    /** Родитель, если узел не корневой. */
    public Optional<TaxonomyNodeId> parentNode() {
        return Optional.ofNullable(parent);
    }

    public boolean isRoot() {
        return parent == null;
    }

    /** Тема — лист дерева: задачи и отметки владения висят только здесь. */
    public boolean isTopic() {
        return childCount == 0;
    }

    /** Раздел — узел с потомками. */
    public boolean isSection() {
        return childCount > 0;
    }
}
