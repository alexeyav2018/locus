package ru.locus.problem;

import java.util.Optional;
import org.springframework.context.annotation.Lazy;
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
 * углубить его. У Теоретического материала ({@code TheoryOnNode}) ответы
 * расходятся: он живёт на любом узле и углублению не мешает.
 *
 * <p>Переезд ({@link #moveTopicContent}) выполняется через
 * {@link ProblemService#rehomeTopic}, а не через репозиторий напрямую:
 * право и транзакция стоят на операции сервиса, и второго входа в правку
 * разметки, минующего их, быть не должно (design.md, «Перевешивание метки —
 * операция области Задач, а не дерева»). Сервис при этом берётся
 * {@link Lazy отложенно}: бины замыкаются в кольцо — {@code TaxonomyService}
 * собирает ответчиков, этот ответчик зовёт {@code ProblemService}, а тот
 * держит {@code TaxonomyService}, — и кольцо это не ошибка устройства,
 * а прямое следствие инверсии: область, отвечающая дереву, отвечает ему
 * своими же средствами. Отложенность размыкает кольцо на уровне бинов,
 * не трогая направления зависимости областей.
 */
@Component
public class ProblemsOnNode implements NodeContent {

    private final ProblemRepository problems;
    private final ProblemService service;

    public ProblemsOnNode(ProblemRepository problems, @Lazy ProblemService service) {
        this.problems = problems;
        this.service = service;
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

    /**
     * Углубление Темы с Задачами: все её Задачи переезжают на Тему-приёмник
     * (ADR-0007). Заморозка Задачи переезду не мешает — ADR-0034, пояснение
     * у самого {@link ProblemService#rehomeTopic}.
     */
    @Override
    public void moveTopicContent(TaxonomyNodeId from, TaxonomyNodeId to) {
        service.rehomeTopic(from, to);
    }

    // countVanishing не переопределяется намеренно: Задачи вместе с Темой
    // не исчезают — они распределяются по приёмникам, и до последней, иначе
    // снятие отклоняется (ProblemService.distribute). Считать здесь нечего;
    // по существу на этот вопрос отвечать будут отметки Владения.
}
