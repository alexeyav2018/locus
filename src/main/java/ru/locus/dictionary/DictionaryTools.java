package ru.locus.dictionary;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.locus.assistant.Schema;
import ru.locus.assistant.Tool;
import ru.locus.assistant.ToolResult;
import ru.locus.assistant.Tools;

/**
 * Инструменты Помощника над словарями Методов и Характеристик (ADR-0056;
 * locus-mcp, design.md, Р6).
 *
 * <p>Два инструмента — каждый отдаёт свой словарь целиком, как экран
 * словарей показывает оба списка: Помощник подбирает разметку Задачи
 * по именам и ссылается на записи идентификаторами. Тонкая обёртка рядом
 * с {@link DictionaryController}: ни правил, ни проверки прав,
 * ни репозитория — словари общие и читаются всеми вошедшими без фильтра
 * (ADR-0027).
 */
@Component
public class DictionaryTools implements Tools {

    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;

    public DictionaryTools(SolutionMethodService methods, CharacteristicService characteristics) {
        this.methods = methods;
        this.characteristics = characteristics;
    }

    @Override
    public List<Tool> tools() {
        return List.of(
                new Tool("list_methods",
                        "Словарь Методов решения целиком, по алфавиту: идентификатор и имя каждого. "
                                + "Метод — приём решения («замена переменной», «теорема о пропорциональных "
                                + "отрезках»), сквозной для Тем; Задача размечается хотя бы одним.",
                        Schema.object().build(),
                        arguments -> ToolResult.json(methods.all().stream()
                                .map(method -> new Entry(method.id().value(), method.name()))
                                .toList())),
                new Tool("list_characteristics",
                        "Словарь Характеристик целиком, по алфавиту: идентификатор и имя каждой. "
                                + "Характеристика — особенность задачи («двойной угол»), служит только "
                                + "поиску; у Задачи их может не быть.",
                        Schema.object().build(),
                        arguments -> ToolResult.json(characteristics.all().stream()
                                .map(characteristic -> new Entry(characteristic.id().value(), characteristic.name()))
                                .toList())));
    }

    /** Запись словаря для модели: идентификатор и имя. */
    public record Entry(long id, String name) {
    }
}
