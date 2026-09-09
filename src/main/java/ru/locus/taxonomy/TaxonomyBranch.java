package ru.locus.taxonomy;

import java.util.List;

/**
 * Узел вместе с уже собранным поддеревом — то, что показывает экран.
 *
 * Дерево собирается в сервисе, а не в шаблоне: шаблон только отображает,
 * а условие сложнее «показать или нет» означало бы, что вычисление сбежало
 * из сервиса (standards.md, «Слои и границы»).
 *
 * Потомки идут по алфавиту имён — ручного порядка братьев нет.
 */
public record TaxonomyBranch(TaxonomyNode node, List<TaxonomyBranch> children) {

    public TaxonomyBranch {
        children = List.copyOf(children);
    }
}
