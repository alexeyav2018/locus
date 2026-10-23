package ru.locus.assistant;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.Map;

/**
 * Обёртка инструмента для SDK MCP — единственное место, где {@link Tool}
 * встречается с протоколом (design.md, Р5 и Р6).
 *
 * <p>Переводит описание инструмента в схему SDK, аргументы вызова —
 * в {@link Arguments}, результат — в {@code CallToolResult}: данные —
 * JSON текстом, собранным тем же {@code JsonMapper}, что у приложения;
 * картинка — {@code ImageContent} JPEG в base64; отказ — результат
 * с признаком ошибки и текстом. Отказ не рвёт соединения: модель
 * читает его и действует дальше, как учитель читает красную строку.
 *
 * <p>Переносит личность. Обработчик инструмента SDK выполняет не на потоке
 * сервлета, а на своём ({@code Schedulers.boundedElastic}), и контекст
 * безопасности, поставленный {@link TurnTokenFilter}, туда не доходит.
 * Поэтому {@link #carry(HttpServletRequest)} на потоке сервлета кладёт
 * {@link Authentication} в контекст транспорта, а {@link #call} на потоке
 * обработчика ставит её в {@code SecurityContextHolder} на время вызова
 * и снимает после. {@code CurrentUser} при этом не меняется: ему всё
 * равно, кто поставил вошедшего — форма, токен или обёртка.
 *
 * <p>Что отказ, а что сбой. Отказы сервисов — нет такой записи, нет прав,
 * неверный аргумент, нарушенное правило — это {@code IllegalArgumentException}
 * и исключения проекта ({@code ru.locus.*}) с текстом, который учитель
 * увидел бы на странице; они возвращаются как есть. Всё прочее — сбой:
 * внутренности стенда наружу не идут, модель получает общий текст,
 * журнал — исключение целиком. Отказ в правах ({@code AccessDeniedException})
 * приходит с английским текстом Spring, поэтому у него свой текст.
 */
final class ToolAdapter {

    /** Ключ, под которым личность едет из потока сервлета в поток обработчика. */
    static final String AUTHENTICATION = "ru.locus.assistant.authentication";

    static final String NO_RIGHTS = "Нет прав на это действие";
    static final String FAILURE = "Не удалось выполнить: сбой на стороне Локуса, подробности в журнале";
    static final String IMAGE_JPEG = "image/jpeg";

    private static final Logger LOG = LoggerFactory.getLogger(ToolAdapter.class);

    private final JsonMapper json;

    ToolAdapter(JsonMapper json) {
        this.json = json;
    }

    /** На потоке сервлета: личность вошедшего — в контекст транспорта. */
    McpTransportContext carry(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(AUTHENTICATION, authentication));
    }

    SyncToolSpecification adapt(Tool tool) {
        McpSchema.Tool description = McpSchema.Tool.builder(tool.name(), tool.inputSchema())
                .description(tool.description())
                .build();
        return SyncToolSpecification.builder()
                .tool(description)
                .callHandler((context, request) -> call(tool, context, request))
                .build();
    }

    /** На потоке обработчика: личность из контекста транспорта — на время вызова. */
    McpSchema.CallToolResult call(Tool tool, McpTransportContext context, McpSchema.CallToolRequest request) {
        SecurityContext carried = SecurityContextHolder.createEmptyContext();
        if (context.get(AUTHENTICATION) instanceof Authentication authentication) {
            carried.setAuthentication(authentication);
        }
        SecurityContextHolder.setContext(carried);
        try {
            Map<String, Object> raw = request.arguments();
            return convert(tool.call(raw == null ? Arguments.none() : Arguments.of(raw)));
        } catch (AccessDeniedException denied) {
            return refusal(NO_RIGHTS);
        } catch (RuntimeException e) {
            if (isRefusal(e)) {
                return refusal(e.getMessage());
            }
            LOG.error("Инструмент «{}» не выполнен", tool.name(), e);
            return refusal(FAILURE);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private McpSchema.CallToolResult convert(ToolResult result) {
        return switch (result) {
            case ToolResult.Json data -> McpSchema.CallToolResult.builder()
                    .addContent(McpSchema.TextContent.builder(json.writeValueAsString(data.value())).build())
                    .isError(false)
                    .build();
            case ToolResult.Image image -> McpSchema.CallToolResult.builder()
                    .addContent(McpSchema.ImageContent
                            .builder(Base64.getEncoder().encodeToString(image.jpeg()), IMAGE_JPEG)
                            .build())
                    .isError(false)
                    .build();
            case ToolResult.Refusal refusal -> refusal(refusal.message());
        };
    }

    private static McpSchema.CallToolResult refusal(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private static boolean isRefusal(RuntimeException e) {
        boolean withText = e.getMessage() != null && !e.getMessage().isBlank();
        return withText && (e instanceof IllegalArgumentException
                || e.getClass().getPackageName().startsWith("ru.locus."));
    }
}
