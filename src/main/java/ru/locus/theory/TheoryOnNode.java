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
 *
 * По той же причине не переопределяются и оба вопроса перестройки
 * ({@code rubricator-restructure}):
 *
 * <ul>
 *   <li>{@code moveTopicContent} — <b>в переезде теория не участвует</b>.
 *       Задачу переезд спасает от того, чтобы остаться на узле, ставшем
 *       Разделом; материалу на Разделе ничего не грозит, и увозить его
 *       с узла, который никуда не делся, значило бы решать за
 *       Администратора, где теперь место его материалу.</li>
 *   <li>{@code countVanishing} — <b>вместе с узлом теория не исчезает</b>:
 *       узел со своими материалами не снимается ни обычным удалением,
 *       ни снятием с распределением. Отказ при этом обязан называть выход:
 *       материал переносится на другой узел обычной правкой
 *       ({@code TheoryService.edit}) — заморозки у теории нет, и никаких
 *       условий на такой перенос не наложено (ADR-0033).</li>
 * </ul>
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
