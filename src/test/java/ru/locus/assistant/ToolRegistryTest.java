package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ClassUtils;
import ru.locus.IntegrationTest;

/**
 * Реестр инструментов — единственный список умений Помощника (ADR-0056).
 *
 * Имя в реестре неповторимо, и повтор — ошибка при старте, а не тихая
 * подмена; имя — латиница строчными с подчёркиваниями, потому что так
 * его видит модель и так его называют навыки.
 */
class ToolRegistryTest {

    private static Tool tool(String name) {
        return new Tool(name, "описание " + name, Schema.object().build(), arguments -> ToolResult.json(name));
    }

    @Test
    void collectsToolsOfEveryAreaInOrder() {
        ToolRegistry registry = new ToolRegistry(List.of(
                () -> List.of(tool("list_topics")),
                () -> List.of(tool("list_methods"), tool("find_problems"))));

        assertThat(registry.names()).containsExactly("list_topics", "list_methods", "find_problems");
        assertThat(registry.find("find_problems")).isPresent();
        assertThat(registry.find("sell_students")).isEmpty();
    }

    @Test
    void sameNameInTwoAreasFailsLoudly() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                () -> List.of(tool("get_problem")),
                () -> List.of(tool("get_problem")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("get_problem")
                .hasMessageContaining("дважды");
    }

    @Test
    void toolNameIsLowercaseLatinWithUnderscores() {
        assertThatThrownBy(() -> tool("GetProblem")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool("get-problem")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool("найти_задачу")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool("1st")).isInstanceOf(IllegalArgumentException.class);
        assertThat(tool("get_problem_page").name()).isEqualTo("get_problem_page");
    }

    @Test
    void toolNeedsDescriptionSchemaAndHandler() {
        assertThatThrownBy(() -> new Tool("x", " ", Schema.object().build(), a -> ToolResult.json(1)))
                .hasMessageContaining("описания");
        assertThatThrownBy(() -> new Tool("x", "описание", null, a -> ToolResult.json(1)))
                .hasMessageContaining("схемы");
        assertThatThrownBy(() -> new Tool("x", "описание", Schema.object().build(), null))
                .hasMessageContaining("обработчика");
    }

    @Test
    void schemaKeepsFieldOrderAndRequiredNames() {
        var schema = Schema.object()
                .property("draft", Schema.integer("номер черновика"))
                .property("page", Schema.integer("номер страницы с единицы"))
                .property("slot", Schema.oneOf("слот", "condition", "solution"))
                .required("draft", "page")
                .build();

        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactly("draft", "page", "slot");
        assertThat(schema.get("required")).isEqualTo(List.of("draft", "page"));
        assertThatThrownBy(() -> Schema.object().required("nope")).hasMessageContaining("«nope»");
    }

    /**
     * Реестр приложения — первая очередь (спека {@code assistant-tools},
     * «Инструментов личного контура и глаголов человека нет»): ровно десять
     * инструментов библиотеки и Черновиков сборки. Одиннадцатый — не ошибка
     * сборки, а решение: инструменты личного контура идут отдельными
     * элементами бэклога (ADR-0054), и этот список правится вместе со спекой.
     */
    @Nested
    class OfTheApplication extends IntegrationTest {

        @Autowired
        private ToolRegistry registry;
        @Autowired
        private List<Tools> areas;

        @Test
        void firstQueueIsExactlyTenNamedTools() {
            assertThat(registry.names()).containsExactlyInAnyOrder(
                    "list_topics", "list_methods", "list_characteristics",
                    "find_problems", "get_problem", "get_problem_page", "create_problem",
                    "list_assembly_drafts", "get_assembly_draft_page", "crop_assembly_draft_page");
        }

        /** Каждая область инструментов стоит под сторожем тонкости {@code ToolsAreThinTest}. */
        @Test
        void everyAreaOfToolsIsGuardedByTheThinnessTest() {
            assertThat(areas).<Class<?>>map(area -> ClassUtils.getUserClass(area))
                    .containsExactlyInAnyOrderElementsOf(ToolsAreThinTest.TOOLS);
        }
    }
}
