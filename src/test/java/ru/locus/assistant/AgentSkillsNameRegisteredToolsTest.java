package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Спека {@code assistant-tools}, «Навыки называют только инструменты реестра»
 * (ADR-0057; design.md agent-runner, Р12).
 *
 * <p>Навык — документ, а не код: имя инструмента в нём компилятор не сверяет.
 * Навык, назвавший выдуманный или удалённый инструмент, не падает — модель
 * тихо ищет обход или отвечает учителю, что не может. Поэтому каждое
 * {@code mcp__locus__<имя>} в файлах {@code agent/} рабочего дерева сверяется
 * с реестром той же сборки. Шаблон {@code mcp__locus__*} в разрешениях —
 * не инструмент и под образец не попадает.
 */
class AgentSkillsNameRegisteredToolsTest {

    /** Корень рабочего места в рабочем дереве — тот же, что уходит в сборку ресурсом. */
    private static final Path AGENT = Path.of("agent");

    private static final Pattern TOOL = Pattern.compile("mcp__locus__([a-z0-9_]+)");

    /** Имена {@code mcp__locus__…} текста, которых нет в реестре, — полным именем, в порядке появления. */
    static Set<String> unknownTools(String text, Collection<String> registered) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = TOOL.matcher(text);
        while (matcher.find()) {
            if (!registered.contains(matcher.group(1))) {
                unknown.add(matcher.group());
            }
        }
        return unknown;
    }

    @Test
    void inventedToolIsNamed() {
        String skill = "Сначала `mcp__locus__list_topics`, затем `mcp__locus__invented_tool`.";

        assertThat(unknownTools(skill, List.of("list_topics"))).containsExactly("mcp__locus__invented_tool");
    }

    @Test
    void permissionTemplateIsNotATool() {
        assertThat(unknownTools("\"allow\": [\"mcp__locus__*\"]", List.of())).isEmpty();
    }

    @Nested
    class OfTheWorkingTree extends IntegrationTest {

        @Autowired
        private ToolRegistry registry;

        @Test
        void everyToolNamedInAgentIsRegistered() throws IOException {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(AGENT)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            assertThat(files).as("файлы рабочего места agent/").isNotEmpty();

            Set<String> named = new LinkedHashSet<>();
            for (Path file : files) {
                String text = Files.readString(file);
                assertThat(unknownTools(text, registry.names()))
                        .as("инструменты вне реестра в %s", file)
                        .isEmpty();
                named.addAll(unknownTools(text, List.of()));
            }
            assertThat(named).as("общие правила называют инструменты").isNotEmpty();
        }

        @Test
        void permissionsAllowOnlyLocusTools() throws IOException {
            JsonNode settings = JsonMapper.builder().build()
                    .readTree(Files.readString(AGENT.resolve(".claude/settings.json")));

            assertThat(settings.path("permissions").path("allow").valueStream().map(JsonNode::asString))
                    .containsExactly("mcp__locus__*");
            assertThat(settings.path("enableAllProjectMcpServers").asBoolean()).isTrue();
        }
    }
}
