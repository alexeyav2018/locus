package ru.locus.mastery;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.taxonomy.NodeContent;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TopicDistribution;

/**
 * Ответ отметок Владения на вопросы дерева «что лежит на этом узле»,
 * «перевесь своё» и «сколько твоего исчезнет».
 *
 * Отметка стоит на паре «Тема × Метод» (ADR-0011), то есть только на Теме
 * (инвариант 3): её наличие и мешает удалить узел обычным снятием,
 * и мешает углубить его без Темы-приёмника — оба вопроса отвечаются одним
 * числом, как у {@code ProblemsOnNode}.
 *
 * <p>Зависит от {@link MasteryRepository} напрямую, минуя
 * {@code MasteryService}, — и это отличие от {@code ProblemsOnNode},
 * который идёт через {@code ProblemService.rehomeTopic}. У операций дерева
 * над отметками нет ни владельца, ни права Учителя: их совершает
 * Администратор над общим узлом, право и транзакция стоят
 * на {@code TaxonomyService} (ADR-0007), а касаются они отметок всех
 * Учителей сразу (ADR-0036). У Задачи переезд — правка разметки со своими
 * правилами, и второго входа в неё, минующего сервис, быть не должно;
 * у отметки правил переезда, кроме слияния, нет, а слияние — дело
 * репозитория (design.md, «Ответы»). Сервис отметок этих методов не зовёт
 * и их не знает.
 */
@Component
public class MasteryOnNode implements NodeContent {

    private final MasteryRepository marks;

    public MasteryOnNode(MasteryRepository marks) {
        this.marks = marks;
    }

    @Override
    public Optional<String> on(TaxonomyNodeId node) {
        int count = marks.countByTopic(node);
        return count == 0 ? Optional.empty() : Optional.of("на Теме стоят отметки Владения (" + count + ")");
    }

    @Override
    public Optional<String> requiringTopic(TaxonomyNodeId node) {
        return on(node);
    }

    /**
     * Углубление Темы: отметки переезжают на Тему-приёмник вместе
     * с Задачами — приёмник есть та же Тема, уточнённая местом в дереве,
     * и суждение Учителя от переезда не меняется (ADR-0007). Занятая
     * на приёмнике ячейка сливается по правилу ADR-0013 (ADR-0039).
     */
    @Override
    public void moveTopicContent(TaxonomyNodeId from, TaxonomyNodeId to) {
        marks.rehomeTopic(from, to);
    }

    /**
     * Снятие Темы с распределением: отметки не распределяются, а исчезают
     * вместе с Темой — получателей несколько, и слияние размазало бы одно
     * суждение по нескольким Темам (ADR-0007). Распределение потому
     * не читается: своей части в нём у отметок нет и быть не может.
     * Число исчезающего Администратор видел заранее — {@link #countVanishing}.
     */
    @Override
    public void distributeTopicContent(TaxonomyNodeId from, TopicDistribution distribution) {
        marks.deleteByTopic(from);
    }

    /**
     * Единственный ответчик, у которого здесь не ноль: отметка, снятая
     * вместе с Темой, невосполнима. Считаются суждения всех Учеников всех
     * Учителей — по владельцу не фильтруется намеренно, наружу только число
     * (ADR-0036); `UNKNOWN` строк не имеет и не считается (ADR-0039).
     */
    @Override
    public int countVanishing(TaxonomyNodeId node) {
        return marks.countByTopic(node);
    }
}
