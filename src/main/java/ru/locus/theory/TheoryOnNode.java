package ru.locus.theory;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.taxonomy.NodeContent;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Ответ теории на вопрос дерева «что лежит на этом узле».
 *
 * Зависимость идёт от {@code theory} к {@code taxonomy} — в ту же сторону,
 * в какую она шла и раньше: материал ссылается на узел. {@code TaxonomyService}
 * о теории не знает и не узнаёт (design.md, «Проверки в чужих областях»).
 *
 * Это первый случай, когда два вопроса {@link NodeContent} расходятся, —
 * и он тот самый, ради которого они были разделены
 * ({@code NodeContent.java:33}). Материал живёт на любом узле: у Раздела
 * он такой же законный, как у Темы, и появление потомка ему ничем
 * не грозит — унаследуется вниз, как наследовался (ADR-0032). Поэтому
 * {@code requiringTopic} здесь не переопределяется: углублению дерева теория
 * не мешает.
 */
@Component
public class TheoryOnNode implements NodeContent {

    private final TheoryMaterialRepository materials;

    public TheoryOnNode(TheoryMaterialRepository materials) {
        this.materials = materials;
    }

    /**
     * Считаются только <b>свои</b> материалы узла.
     *
     * Унаследованные лежат не здесь и с исчезновением узла ничего не теряют;
     * посчитай их — и ни один узел внутри Раздела с материалом нельзя было бы
     * удалить, причём отказ ссылался бы на материал, которого на узле нет.
     */
    @Override
    public Optional<String> on(TaxonomyNodeId node) {
        int count = materials.countByNode(node);
        return count == 0
                ? Optional.empty()
                : Optional.of("на узле есть Теоретические материалы (" + count + ")");
    }
}
