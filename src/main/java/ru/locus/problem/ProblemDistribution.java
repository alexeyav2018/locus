package ru.locus.problem;

import java.util.Map;
import java.util.Set;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TopicDistribution;

/**
 * Поштучное распределение Задач снимаемой Темы: каждой — своя Тема-приёмник
 * (ADR-0007).
 *
 * Та часть {@link TopicDistribution}, которую читает область Задач. Дерево
 * получает её как распределение вообще и видит только приёмников; обратно
 * через {@code NodeContent.distributeTopicContent} она приходит в
 * {@link ProblemsOnNode}, и тот узнаёт в ней свою карту.
 *
 * <p>Карта принимается <b>целиком</b>: полноту — каждая Задача Темы названа,
 * посторонних нет — проверяет {@link ProblemService#distribute} до первой
 * правки (design.md, «Поштучное распределение принимает карту, а не список»).
 */
public record ProblemDistribution(Map<ProblemId, TaxonomyNodeId> destinations) implements TopicDistribution {

    public ProblemDistribution {
        if (destinations == null) {
            throw new IllegalArgumentException("Распределение должно быть указано");
        }
        destinations = Map.copyOf(destinations);
    }

    @Override
    public Set<TaxonomyNodeId> receivers() {
        return Set.copyOf(destinations.values());
    }
}
