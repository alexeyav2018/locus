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
 * <p>Два инструмента чтения — каждый отдаёт свой словарь целиком, как экран
 * словарей показывает оба списка: Помощник подбирает разметку Задачи
 * по именам и ссылается на записи идентификаторами. Тонкая обёртка рядом
 * с {@link DictionaryController}: ни правил, ни проверки прав,
 * ни репозитория — словари общие и читаются всеми вошедшими без фильтра
 * (ADR-0027).
 *
 * <p>{@code create_method} заводит Метод тем же
 * {@link SolutionMethodService#create}, что экран словарей: права
 * Администратора и неповторимость имени без учёта регистра проверяет
 * сервис (problem-intake, design.md, Р7). Нужен навыку разбора листа —
 * Метод, которого в словаре нет, заводится после согласия учителя,
 * не уводя его посреди разговора на другую страницу.
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
                new Tool("create_method",
                        "Завести Метод решения в словарь (только Администратор) — после того, как "
                                + "учитель согласился. Имя неповторимо без учёта регистра: повтор "
                                + "отклоняется. Ответ — идентификатор и имя нового Метода.",
                        Schema.object()
                                .property("name", Schema.string("имя Метода, например «замена переменной»"))
                                .required("name")
                                .build(),
                        arguments -> {
                            SolutionMethod created = methods.method(methods.create(arguments.requireString("name")));
                            return ToolResult.json(new Entry(created.id().value(), created.name()));
                        }),
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
