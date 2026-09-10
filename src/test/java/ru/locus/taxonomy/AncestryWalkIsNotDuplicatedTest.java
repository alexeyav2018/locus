package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Подъём по родителям на весь проект один, и живёт он в рубрикаторе.
 *
 * <p>Наследование Теоретических материалов вниз по дереву (ADR-0032) строится
 * на цепочке предков — {@code TaxonomyService.ancestry}. Соблазн написать свой
 * подъём в области теории настоящий: цикл в три строки, и ходить за ним
 * в чужую область не надо. Цена — второй предел глубины; разойдясь, пределы
 * ведут себя по-разному, и там, где один даёт ошибку, другой крутится вечно.
 * Это ровно та причина, по которой рядом стоит
 * {@code SubtreeWalkIsNotDuplicatedTest}: обход дерева заводится единожды
 * (standards.md, «Данные»).
 *
 * <p>Проверка смотрит на исходный текст, а не на вызовы: второй подъём
 * появится не правкой сигнатуры, а новым циклом в новом методе — компилятору
 * он не мешает, тестам наследования тоже, потому что на дереве из двух
 * уровней два подъёма дают один и тот же ответ.
 *
 * <p>Признак подъёма — вопрос узлу о его родителе: {@code parent()} или
 * {@code parentNode()}. Другого способа шагнуть вверх у дерева нет, и вне
 * рубрикатора такой вопрос не задают вовсе — областям хватает готовой
 * цепочки.
 *
 * <p>Проверки о том, что подъём всё-таки где-то есть, не украшение: без них
 * правило выполнялось бы и в мире, где подъёма не осталось нигде.
 */
class AncestryWalkIsNotDuplicatedTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final Path TAXONOMY = SOURCES.resolve("ru/locus/taxonomy");

    /** Слова, которыми у узла спрашивают родителя. */
    private static final List<String> STEPS_UP = List.of("parent()", "parentNode()");

    @Test
    void noAreaOutsideTheTaxonomyAsksANodeForItsParent() {
        for (Path source : sourcesOutsideTheTaxonomy()) {
            String text = text(source);
            for (String step : STEPS_UP) {
                assertThat(text)
                        .as("%s: вверх по дереву поднимается только рубрикатор, второго подъёма быть не должно",
                                source.getFileName())
                        .doesNotContain(step);
            }
        }
    }

    @Test
    void theAscentItselfLivesInTheTaxonomyService() {
        String text = text(sourceOf(TaxonomyService.class));

        assertThat(text).as("цепочка предков — здесь").contains("ancestry(");
        assertThat(text).as("и она ограничена по числу шагов").contains("MAX_DEPTH");
    }

    /**
     * Предел глубины один на спуск и на подъём. Появление его имени в третьем
     * файле означает третий обход дерева.
     */
    @Test
    void theDepthLimitIsNamedInTwoPlacesOnly() {
        List<Path> naming = allSources().stream()
                .filter(source -> text(source).contains("MAX_DEPTH"))
                .toList();

        assertThat(naming)
                .as("предел глубины знают спуск в репозитории и подъём в сервисе — и никто больше")
                .containsExactlyInAnyOrder(sourceOf(TaxonomyRepository.class), sourceOf(TaxonomyService.class));
    }

    private static List<Path> sourcesOutsideTheTaxonomy() {
        List<Path> outside = allSources().stream()
                .filter(source -> !source.startsWith(TAXONOMY))
                .toList();
        assertThat(outside).as("исходники вне рубрикатора найдены").isNotEmpty();
        return outside;
    }

    private static List<Path> allSources() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            List<Path> java = files.filter(file -> file.toString().endsWith(".java")).toList();
            assertThat(java).as("исходники приложения найдены").isNotEmpty();
            return java;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path sourceOf(Class<?> type) {
        return SOURCES.resolve(type.getName().replace('.', '/') + ".java");
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
