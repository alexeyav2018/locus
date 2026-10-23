package ru.locus.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Инструмент Помощника — тонкая обёртка метода сервиса, которую Помощник
 * зовёт по протоколу MCP (ADR-0056).
 *
 * <p>Инструмент — второй клиент сервисного слоя, наравне с контроллером:
 * обработчик разбирает аргументы, зовёт сервис и укладывает ответ,
 * и больше ничего — ни предметной логики, ни проверки прав, ни фильтра
 * по владельцу. Всё это делает сервис от имени вошедшего, которого ставит
 * сервер по токену Реплики (ADR-0057), — тот же вошедший, что получил бы
 * контроллер страницы.
 *
 * <p>Типов SDK MCP здесь нет намеренно: их видит только сервер
 * ({@code McpServerConfiguration}), а области объявляют инструменты этим
 * типом и о протоколе не знают — как о S3 не знает никто выше
 * {@code ru.locus.file} (design.md, Р1 и Р6).
 *
 * <p>Имя — латиница строчными, цифры и подчёркивание: так его видит
 * модель ({@code mcp__locus__<имя>}), и так его называют навыки,
 * которые сверяются с реестром тестом (ADR-0057).
 *
 * @param name        имя инструмента, неповторимое в реестре
 * @param description описание для модели — по-русски, как весь интерфейс
 * @param inputSchema JSON-схема аргументов ({@link Schema})
 * @param handler     обработчик: аргументы вызова → результат
 */
public record Tool(String name,
                   String description,
                   Map<String, Object> inputSchema,
                   Function<Arguments, ToolResult> handler) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    public Tool {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Имя инструмента «" + name
                    + "» не годится: латиница строчными, цифры и подчёркивание, начиная с буквы");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("У инструмента «" + name + "» нет описания");
        }
        if (inputSchema == null) {
            throw new IllegalArgumentException("У инструмента «" + name + "» нет схемы аргументов");
        }
        if (handler == null) {
            throw new IllegalArgumentException("У инструмента «" + name + "» нет обработчика");
        }
        // Порядок ключей сохраняется: схему читает модель, и «type» впереди
        // «properties» читается лучше, чем вперемешку.
        inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }

    /** Вызов обработчика — то, что делает сервер, разобрав запрос. */
    public ToolResult call(Arguments arguments) {
        return handler.apply(arguments);
    }
}
