package ru.locus.problem;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.taxonomy.NodeContent;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Ответ библиотеки Задач на вопрос дерева «что лежит на этом узле».
 *
 * Зависимость идёт от {@code problem} к {@code taxonomy} — в ту же сторону,
 * в какую она шла и раньше: Задача ссылается на узлы дерева. Обратной связи
 * между областями не появляется, {@code TaxonomyService} о Задачах не знает
 * и знать не должен (design.md, «Проверки в чужих областях»).
 *
 * Оба вопроса отвечаются одинаково, и это не совпадение: Задача висит только
 * на Теме (инвариант 1), поэтому её наличие и мешает удалить узел, и мешает
 * углубить его. У Теоретического материала, который придёт с
 * {@code theory-materials}, ответы разойдутся: он живёт на любом узле
 * и углублению не мешает.
 */
@Component
public class ProblemsOnNode implements NodeContent {

    private final ProblemRepository problems;

    public ProblemsOnNode(ProblemRepository problems) {
        this.problems = problems;
    }

    @Override
    public Optional<String> on(TaxonomyNodeId node) {
        int count = problems.countByTopic(node);
        return count == 0 ? Optional.empty() : Optional.of("на Теме размечены Задачи (" + count + ")");
    }

    @Override
    public Optional<String> requiringTopic(TaxonomyNodeId node) {
        return on(node);
    }
}
