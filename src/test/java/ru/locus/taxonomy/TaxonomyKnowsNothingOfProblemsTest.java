package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Задача 5.2: дерево научилось отказывать из-за Задач, не узнав о них.
 *
 * Проверка «на узле есть содержимое» пришла в {@link TaxonomyService} через
 * вопрос {@link NodeContent}, а не прямым вызовом репозитория Задач. Разница
 * не в стиле: {@code problem} уже зависит от {@code taxonomy}
 * идентификаторами узлов, и обратная ссылка замкнула бы области в кольцо —
 * а следом то же самое сделали бы теория и отметки владения (design.md,
 * «Проверки в чужих областях»).
 *
 * Смотрим на исходный текст, а не на поля класса: зависимость может прийти
 * и статическим вызовом, и типом в сигнатуре, и её не видно отражением.
 * Ошибка при этом не падает — она просто накапливает связи между областями.
 */
class TaxonomyKnowsNothingOfProblemsTest {

    @Test
    void theTreeServiceDoesNotMentionTheProblemArea() {
        assertThat(sourceOf(TaxonomyService.class))
                .as("сервис дерева не должен знать о пакете Задач ни одной строкой")
                .doesNotContain("ru.locus.problem");
    }

    @Test
    void neitherDoesTheTreeRepository() {
        assertThat(sourceOf(TaxonomyRepository.class)).doesNotContain("ru.locus.problem");
    }

    /**
     * Экрана это не касается: список Задач Темы живёт в правой части экрана
     * дерева, и контроллер о Задачах знает намеренно (design.md, «Экран»).
     * Зависимость там односторонняя и не создаёт кольца между сервисами.
     */
    @Test
    void theScreenIsTheOneAllowedException() {
        assertThat(sourceOf(TaxonomyController.class))
                .as("список Задач Темы собирается здесь — это осознанное исключение")
                .contains("ru.locus.problem");
    }

    private static String sourceOf(Class<?> type) {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
