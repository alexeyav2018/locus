package ru.locus.assistant;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Событие вывода Claude Code в {@code --output-format stream-json} — одна
 * строка JSON (design.md agent-runner, Р8).
 *
 * <p>Исполнителю Реплики нужно из потока немногое: сеанс ({@link Init}),
 * вызовы инструментов ({@link ToolCalls}) и итог ({@link Result}).
 * Всё прочее — ответ текстом по ходу, результаты инструментов, служебные
 * строки вроде {@code rate_limit_event} — {@link Other}: исход Реплики
 * берётся из итога, а не из промежуточного текста. Неразбираемая строка —
 * тоже {@link Other}, а не отказ: строку, которую Локус не понял, процесс
 * написал не для него, и Реплика из-за неё не рвётся (если итога так
 * и не будет, причину «не удалось» назовёт хвост stderr).
 *
 * <p>Вызовов в одной строке может быть несколько: Claude Code 2.1.310
 * пишет блоки сообщения модели по одному в строке, но формат допускает
 * несколько блоков {@code tool_use} в одном сообщении, и молча терять
 * строку хода из-за этого незачем.
 */
public sealed interface StreamEvent {

    /** Предел длины аргументов вызова в строке хода — в знаках. */
    int ARGUMENTS_LIMIT = 500;

    /** Начало сеанса: идентификатор, по которому Разговор продолжится {@code --resume}. */
    record Init(String sessionId) implements StreamEvent {
    }

    /** Вызовы инструментов из одного сообщения модели, в порядке вызова. */
    record ToolCalls(List<ToolCall> calls) implements StreamEvent {
        public ToolCalls {
            calls = List.copyOf(calls);
        }
    }

    /**
     * Вызов инструмента.
     *
     * @param name      имя, как его видит модель ({@code mcp__locus__…})
     * @param arguments аргументы JSON-текстом, не длиннее {@link #ARGUMENTS_LIMIT} знаков
     */
    record ToolCall(String name, String arguments) {
    }

    /**
     * Итог Реплики — последняя строка вывода.
     *
     * @param kind         вид итога ({@code success}, {@code error_max_turns}, …)
     * @param error        признак ошибки
     * @param text         ответ Помощника либо, у ошибки, её текст; может быть пустым
     * @param costUsd      стоимость в долларах; {@code null}, если процесс её не назвал
     * @param inputTokens  токены входа — сумма прямых, записанных в кеш и прочитанных из кеша
     * @param outputTokens токены выхода
     * @param turns        число ходов
     */
    record Result(String kind,
                  boolean error,
                  String text,
                  BigDecimal costUsd,
                  long inputTokens,
                  long outputTokens,
                  int turns) implements StreamEvent {

        /** Итог исчерпанного предела ходов. */
        public boolean stepLimit() {
            return "error_max_turns".equals(kind);
        }
    }

    /** Строка, из которой исполнителю ничего не нужно, или неразбираемая. */
    record Other() implements StreamEvent {
    }

    /** Разобрать одну строку вывода. Никогда не бросает. */
    static StreamEvent parse(String line) {
        JsonNode node;
        try {
            node = JsonMapper.shared().readTree(line == null ? "" : line);
        } catch (JacksonException e) {
            return new Other();
        }
        if (node == null || !node.isObject()) {
            return new Other();
        }
        String type = node.path("type").asString("");
        if ("system".equals(type) && "init".equals(node.path("subtype").asString(""))) {
            String session = node.path("session_id").asString("");
            return session.isBlank() ? new Other() : new Init(session);
        }
        if ("assistant".equals(type)) {
            return toolCalls(node.path("message").path("content"));
        }
        if ("result".equals(type)) {
            return result(node);
        }
        return new Other();
    }

    private static StreamEvent toolCalls(JsonNode content) {
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : content.values()) {
            if ("tool_use".equals(block.path("type").asString(""))) {
                String name = block.path("name").asString("");
                if (!name.isBlank()) {
                    calls.add(new ToolCall(name, truncate(block.path("input").toString())));
                }
            }
        }
        return calls.isEmpty() ? new Other() : new ToolCalls(calls);
    }

    private static StreamEvent result(JsonNode node) {
        JsonNode usage = node.path("usage");
        long input = usage.path("input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0);
        JsonNode cost = node.path("total_cost_usd");
        return new Result(
                node.path("subtype").asString(""),
                node.path("is_error").asBoolean(false),
                text(node),
                cost.isNumber() ? cost.decimalValue() : null,
                input,
                usage.path("output_tokens").asLong(0),
                node.path("num_turns").asInt(0));
    }

    /** Ответ — поле {@code result}; у итога с ошибкой его нет, текст — в списке {@code errors}. */
    private static String text(JsonNode node) {
        JsonNode result = node.path("result");
        if (result.isString()) {
            return result.asString();
        }
        List<String> errors = new ArrayList<>();
        for (JsonNode error : node.path("errors").values()) {
            if (error.isString()) {
                errors.add(error.asString());
            }
        }
        return String.join("; ", errors);
    }

    /** Не длиннее предела в знаках; обрезанное кончается многоточием и не рвёт суррогатную пару. */
    private static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= ARGUMENTS_LIMIT) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, ARGUMENTS_LIMIT - 1)) + "…";
    }
}
