package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.TreeSet;
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
 *
 * <p>Спека {@code assistant-tools}, «Навык рабочего места доходит до Помощника
 * в клетке» (design.md intake-skill, Р1–Р2). Клетка {@code --tools ""} отключает
 * и инструмент {@code Skill}, поэтому навык доходит до модели только импортом
 * {@code @путь} из общих правил {@code agent/CLAUDE.md}. Навык без импорта
 * и импорт без файла не падают — Помощник тихо работает без навыка. Импорт
 * распознаётся отдельной строкой: {@code @} и путь от {@code agent/} без пробелов.
 */
class AgentSkillsNameRegisteredToolsTest {

    /** Корень рабочего места в рабочем дереве — тот же, что уходит в сборку ресурсом. */
    private static final Path AGENT = Path.of("agent");

    private static final Pattern TOOL = Pattern.compile("mcp__locus__([a-z0-9_]+)");

    private static final Pattern IMPORT = Pattern.compile("^@(\\S+)\\s*$", Pattern.MULTILINE);

    private static final String SKILL = "SKILL.md";

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

    /** Пути импортов {@code @…} текста — как написаны, в порядке появления. */
    static Set<String> imports(String text) {
        Set<String> imports = new LinkedHashSet<>();
        Matcher matcher = IMPORT.matcher(text);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    /** Навыки — пути {@code SKILL.md} от {@code agent/}, — которых общие правила не импортируют. */
    static Set<String> skillsWithoutImport(Collection<String> skills, String rules) {
        Set<String> imported = new LinkedHashSet<>();
        for (String path : imports(rules)) {
            imported.add(normalized(path));
        }
        Set<String> missing = new TreeSet<>(skills);
        missing.removeAll(imported);
        return missing;
    }

    /** Импорты общих правил, не ведущие ни к одному из файлов рабочего места (пути от {@code agent/}). */
    static Set<String> importsWithoutFile(String rules, Collection<String> files) {
        Set<String> missing = new LinkedHashSet<>();
        for (String path : imports(rules)) {
            if (!files.contains(normalized(path))) {
                missing.add("@" + path);
            }
        }
        return missing;
    }

    private static String normalized(String path) {
        return slashed(Path.of(path).normalize());
    }

    private static String slashed(Path path) {
        List<String> names = new ArrayList<>();
        path.forEach(name -> names.add(name.toString()));
        return String.join("/", names);
    }

    @Test
    void skillWithoutImportIsNamed() {
        String rules = "# Правила\n\n@.claude/skills/first/SKILL.md\n";

        assertThat(skillsWithoutImport(
                        List.of(".claude/skills/first/SKILL.md", ".claude/skills/second/SKILL.md"), rules))
                .containsExactly(".claude/skills/second/SKILL.md");
    }

    @Test
    void importInsideALineIsNotAnImport() {
        String rules = "Навык лежит в @.claude/skills/first/SKILL.md, но не подключён.\n";

        assertThat(skillsWithoutImport(List.of(".claude/skills/first/SKILL.md"), rules))
                .containsExactly(".claude/skills/first/SKILL.md");
    }

    @Test
    void importWithoutFileIsNamed() {
        String rules = "@.claude/skills/first/SKILL.md\n@.claude/skills/gone/SKILL.md\n@../pom.xml\n";

        assertThat(importsWithoutFile(rules, List.of("CLAUDE.md", ".claude/skills/first/SKILL.md", "pom.xml")))
                .containsExactly("@.claude/skills/gone/SKILL.md", "@../pom.xml");
    }

    @Test
    void importWithDotSegmentsLeadsToTheFile() {
        String rules = "@./.claude/skills/first/../first/SKILL.md\n";

        assertThat(importsWithoutFile(rules, List.of(".claude/skills/first/SKILL.md"))).isEmpty();
        assertThat(skillsWithoutImport(List.of(".claude/skills/first/SKILL.md"), rules)).isEmpty();
    }

    @Test
    void everySkillOfTheWorkingTreeIsImported() throws IOException {
        List<String> files;
        try (Stream<Path> walk = Files.walk(AGENT)) {
            files = walk.filter(Files::isRegularFile).map(file -> slashed(AGENT.relativize(file))).toList();
        }
        List<String> skills = files.stream()
                .filter(file -> file.matches("\\.claude/skills/[^/]+/" + SKILL))
                .toList();
        String rules = Files.readString(AGENT.resolve("CLAUDE.md"));

        assertThat(skills).as("навыки рабочего места agent/").isNotEmpty();
        assertThat(skillsWithoutImport(skills, rules)).as("навыки без импорта в agent/CLAUDE.md").isEmpty();
        assertThat(importsWithoutFile(rules, files)).as("импорты agent/CLAUDE.md без файла в agent/").isEmpty();
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
