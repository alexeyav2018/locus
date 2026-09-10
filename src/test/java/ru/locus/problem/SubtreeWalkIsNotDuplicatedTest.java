package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.locus.taxonomy.TaxonomyRepository;

/**
 * Обход поддерева на весь проект один, и живёт он в рубрикаторе.
 *
 * <p>Поиск по узлу разворачивает поддерево вызовом
 * {@code TaxonomyService.subtree}, а не своим рекурсивным запросом. Соблазн
 * написать его здесь настоящий: один поход в базу вместо двух, и читается
 * такой запрос лучше. Цена — второй обход дерева, а вместе с ним второй
 * предел глубины; разойдясь, они теряют узлы, и происходит это молча,
 * в глубоком дереве, где проверить некому (standards.md, «Данные»:
 * один способ хранения иерархии и один обход).
 *
 * <p>Проверка смотрит на исходный текст, а не на вызовы: второй обход
 * появится не правкой сигнатуры, а новой строкой SQL в новом методе —
 * компилятору она не мешает, тестам поиска тоже, потому что на мелком дереве
 * два обхода дают один и тот же ответ.
 *
 * <p>Второй тест здесь не украшение: без него правило выполнялось бы
 * и в мире, где обхода не осталось нигде.
 */
class SubtreeWalkIsNotDuplicatedTest {

    private static final Path PROBLEM_SOURCES = Path.of("src/main/java/ru/locus/problem");

    @Test
    void theProblemAreaCarriesNoRecursiveQueryOfItsOwn() {
        for (Path source : sources()) {
            assertThat(text(source).toLowerCase())
                    .as("%s: обход поддерева живёт в рубрикаторе, второго заводить нельзя",
                            source.getFileName())
                    .doesNotContain("with recursive");
        }
    }

    @Test
    void theWalkItselfLivesInTheTaxonomyRepository() {
        Path source = Path.of("src/main/java",
                TaxonomyRepository.class.getName().replace('.', '/') + ".java");

        assertThat(text(source).toLowerCase())
                .as("единственный обход дерева — здесь")
                .contains("with recursive");
    }

    private static List<Path> sources() {
        try (Stream<Path> files = Files.list(PROBLEM_SOURCES)) {
            List<Path> java = files.filter(file -> file.toString().endsWith(".java")).toList();
            assertThat(java).as("исходники области Задач найдены").isNotEmpty();
            return java;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Path source) {
        assertThat(source).as("исходный текст %s найден", source).exists();
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
