package ru.locus.mastery;

import ru.locus.taxonomy.TaxonomyNode;

import java.util.List;

/**
 * Узел дерева на экране Владения — узел рубрикатора с уже вычисленным
 * распределением его ячеек и собранным поддеревом (`mastery-views`).
 *
 * Узел без единой ячейки в своём поддереве показывается с {@code
 * distribution.cells() == 0} — «ячеек нет», а не скрывается: скрытый
 * Раздел выглядел бы как отсутствующий в библиотеке (design.md).
 */
public record MasteryBranch(TaxonomyNode node, Distribution distribution, List<MasteryBranch> children) {

    public MasteryBranch {
        if (node == null) {
            throw new IllegalArgumentException("У ветви владения должен быть узел рубрикатора");
        }
        if (distribution == null) {
            throw new IllegalArgumentException("У ветви владения должно быть распределение");
        }
        children = List.copyOf(children);
    }
}
