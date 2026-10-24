package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.locus.assistant.StreamEvent.Init;
import ru.locus.assistant.StreamEvent.Other;
import ru.locus.assistant.StreamEvent.Result;
import ru.locus.assistant.StreamEvent.ToolCall;
import ru.locus.assistant.StreamEvent.ToolCalls;

/**
 * Разбор строки {@code stream-json} (design.md agent-runner, Р8).
 *
 * Заготовки в {@code assistant/stream/} сняты с настоящего вывода Claude
 * Code 2.1.310 ({@code -p --output-format stream-json --verbose}) на заглушке
 * MCP спайка {@code agent-spike}: по строке на файл, как процесс их пишет.
 * У {@code init} обнулены списки окружения снявшего (навыки, плагины,
 * каталоги) — форма строки та же.
 */
class StreamEventTest {

    @Test
    void initGivesTheSession() {
        assertThat(StreamEvent.parse(line("init")))
                .isEqualTo(new Init("c7cb8d43-bb5a-4432-b82b-cfd10cbf76f1"));
    }

    @Test
    void toolUseGivesTheCallWithItsArguments() {
        assertThat(StreamEvent.parse(line("tool-call")))
                .isEqualTo(new ToolCalls(List.of(new ToolCall("mcp__spike__whoami", "{}"))));
    }

    @Test
    void answerTextToolResultAndServiceLinesAreOther() {
        assertThat(StreamEvent.parse(line("answer-text"))).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse(line("tool-result"))).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse(line("rate-limit"))).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse(line("thinking-tokens"))).isInstanceOf(Other.class);
    }

    @Test
    void successGivesTheAnswerCostAndTokens() {
        Result result = (Result) StreamEvent.parse(line("result-success"));

        assertThat(result.kind()).isEqualTo("success");
        assertThat(result.error()).isFalse();
        assertThat(result.stepLimit()).isFalse();
        assertThat(result.text()).startsWith("Инструмент `mcp__spike__whoami` вернул");
        // как записал процесс; до шести знаков округлит колонка базы
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.0014156400000000001"));
        // вход — сумма прямых, записанных в кеш и прочитанных из кеша: 4 + 6506 + 6404
        assertThat(result.inputTokens()).isEqualTo(12914);
        assertThat(result.outputTokens()).isEqualTo(100);
        assertThat(result.turns()).isEqualTo(2);
    }

    @Test
    void maxTurnsIsAStepLimitWithTheErrorText() {
        Result result = (Result) StreamEvent.parse(line("result-max-turns"));

        assertThat(result.kind()).isEqualTo("error_max_turns");
        assertThat(result.error()).isTrue();
        assertThat(result.stepLimit()).isTrue();
        assertThat(result.text()).isEqualTo("Reached maximum number of turns (1)");
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.0019989"));
        assertThat(result.inputTokens()).isEqualTo(2 + 9556);
        assertThat(result.outputTokens()).isEqualTo(175);
    }

    @Test
    void longArgumentsAreCutToTheLimit() {
        String longText = "ж".repeat(2000);
        String line = line("tool-call").replace("\"input\":{}", "\"input\":{\"text\":\"" + longText + "\"}");

        ToolCall call = ((ToolCalls) StreamEvent.parse(line)).calls().getFirst();

        assertThat(call.arguments()).hasSize(StreamEvent.ARGUMENTS_LIMIT).endsWith("…")
                .startsWith("{\"text\":\"жжж");
    }

    @Test
    void cutDoesNotSplitASurrogatePair() {
        String line = line("tool-call").replace("\"input\":{}", "\"input\":{\"t\":\"" + "😀".repeat(600) + "\"}");

        String arguments = ((ToolCalls) StreamEvent.parse(line)).calls().getFirst().arguments();

        assertThat(arguments.codePointCount(0, arguments.length())).isEqualTo(StreamEvent.ARGUMENTS_LIMIT);
        assertThat(Character.isHighSurrogate(arguments.charAt(arguments.length() - 2))).isFalse();
    }

    @Test
    void severalCallsInOneMessageAreAllKept() {
        String line = line("tool-call").replace(
                "\"content\":[{\"type\":\"tool_use\"",
                "\"content\":[{\"type\":\"tool_use\",\"id\":\"x\",\"name\":\"mcp__locus__list_topics\",\"input\":{}},"
                        + "{\"type\":\"tool_use\"");

        assertThat(((ToolCalls) StreamEvent.parse(line)).calls())
                .extracting(ToolCall::name)
                .containsExactly("mcp__locus__list_topics", "mcp__spike__whoami");
    }

    @Test
    void unparseableLineIsOther() {
        assertThat(StreamEvent.parse("не JSON")).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse("{\"type\":\"result\"")).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse("[1, 2]")).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse("")).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse(null)).isInstanceOf(Other.class);
        assertThat(StreamEvent.parse("{\"type\":\"system\",\"subtype\":\"init\"}")).isInstanceOf(Other.class);
    }

    @Test
    void resultWithoutUsageOrCostIsStillAResult() {
        Result result = (Result) StreamEvent.parse("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true}");

        assertThat(result.error()).isTrue();
        assertThat(result.costUsd()).isNull();
        assertThat(result.inputTokens()).isZero();
        assertThat(result.text()).isEmpty();
    }

    static String line(String name) {
        try (InputStream in = StreamEventTest.class.getResourceAsStream("/assistant/stream/" + name + ".jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
