package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.Map;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Вызов инструмента реестра от имени вошедшего — так, как его увидела бы
 * модель: через {@link ToolAdapter}, с переносом личности в контекст
 * вызова и переводом результата в {@code CallToolResult}. Транспорт
 * и токен здесь не участвуют — их сторожит {@code TurnTokenGuardsTheServerTest},
 * а сквозной путь по HTTP — {@code EveryToolAnswersTheClientTest}.
 */
final class ToolCalls {

    private final ToolRegistry registry;
    private final ToolAdapter adapter;
    private final JsonMapper json;

    ToolCalls(ToolRegistry registry, JsonMapper json) {
        this.registry = registry;
        this.adapter = new ToolAdapter(json);
        this.json = json;
    }

    /** Вызов от имени учётной записи; после вызова вошедшего нет, как и после запроса по HTTP. */
    McpSchema.CallToolResult call(TestAccounts.Account caller, String tool, Map<String, Object> arguments) {
        Tool found = registry.find(tool)
                .orElseThrow(() -> new AssertionError("В реестре нет инструмента «" + tool + "»"));
        LoggedIn.as(caller);
        try {
            // Перенос личности читает вошедшего из контекста потока; сам запрос ему не нужен.
            return adapter.call(found, adapter.carry(null),
                    McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        } finally {
            LoggedIn.nobody();
        }
    }

    /** Данные успешного ответа — разобранный JSON его текстового содержимого. */
    JsonNode json(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as("ответ — не отказ: %s", text(result)).isFalse();
        return json.readTree(text(result));
    }

    /** Картинка успешного ответа — байты JPEG. */
    static byte[] jpeg(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as("ответ — не отказ: %s", text(result)).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst()).isInstanceOf(McpSchema.ImageContent.class);
        McpSchema.ImageContent image = (McpSchema.ImageContent) result.content().getFirst();
        assertThat(image.mimeType()).isEqualTo("image/jpeg");
        byte[] bytes = Base64.getDecoder().decode(image.data());
        assertThat(bytes).as("JPEG начинается с FF D8").startsWith((byte) 0xFF, (byte) 0xD8);
        return bytes;
    }

    /** Текст отказа; ответ без признака ошибки роняет тест. */
    static String refusal(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as("ожидался отказ, а пришло: %s", text(result)).isTrue();
        return text(result);
    }

    /** Текстовое содержимое ответа — данные или отказ; у картинки его нет. */
    static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst()
                .orElse("");
    }
}
