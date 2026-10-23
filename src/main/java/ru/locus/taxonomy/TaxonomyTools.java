package ru.locus.taxonomy;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.locus.assistant.Schema;
import ru.locus.assistant.Tool;
import ru.locus.assistant.ToolResult;
import ru.locus.assistant.Tools;

/**
 * Инструменты Помощника над рубрикатором (ADR-0056; locus-mcp, design.md, Р6).
 *
 * <p>Один инструмент — {@code list_topics}: Темы с полными путями, то,
 * из чего Помощник выбирает Тему разметки, как форма Задачи выбирает
 * из {@link TaxonomyService#topicPaths}. Разделов в списке нет: Задача
 * и отметка живут только на листьях (ADR-0006), и предлагать модели узел,
 * на который Задачу всё равно не привязать, незачем.
 *
 * <p>Тонкая обёртка рядом с {@link TaxonomyController}: ни правил,
 * ни проверки прав, ни репозитория — дерево общее и читается всеми
 * вошедшими без фильтра (ADR-0027).
 */
@Component
public class TaxonomyTools implements Tools {

    private final TaxonomyService taxonomy;

    public TaxonomyTools(TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Override
    public List<Tool> tools() {
        return List.of(new Tool("list_topics",
                "Темы рубрикатора — листья дерева, на которые размечаются Задачи: "
                        + "идентификатор и полный путь от корня через « / » "
                        + "(например, «Алгебра / Квадратные уравнения»). Разделов в списке нет: "
                        + "Задача живёт только на Теме.",
                Schema.object().build(),
                arguments -> ToolResult.json(taxonomy.topicPaths().stream()
                        .map(path -> new Topic(path.id().value(), path.path()))
                        .toList())));
    }

    /** Тема для модели: идентификатор узла и путь от корня. */
    public record Topic(long id, String path) {
    }
}
