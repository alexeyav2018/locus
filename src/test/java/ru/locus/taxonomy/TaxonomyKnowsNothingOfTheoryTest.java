package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Задача 5.2: дерево научилось отказывать из-за Теоретических материалов,
 * не узнав о них.
 *
 * Второе содержимое пришло к дереву тем же путём, что и первое, — вопросом
 * {@link NodeContent}, а не прямым вызовом репозитория теории. Ради этого
 * вопрос и заводился: цена появления содержимого — реализация интерфейса
 * в своей области, а не правка дерева (design.md, «Проверки в чужих
 * областях»). Прямой вызов замкнул бы {@code taxonomy} и {@code theory}
 * в кольцо: {@code theory} уже зависит от {@code taxonomy} идентификаторами
 * узлов и подъёмом по предкам.
 *
 * Смотрим на исходный текст, а не на поля класса: зависимость может прийти
 * и статическим вызовом, и типом в сигнатуре, и её не видно отражением.
 * Ошибка при этом не падает — она просто накапливает связи между областями.
 */
class TaxonomyKnowsNothingOfTheoryTest {

    @Test
    void theTreeServiceDoesNotMentionTheTheoryArea() {
        assertThat(sourceOf(TaxonomyService.class))
                .as("сервис дерева не должен знать о пакете теории ни одной строкой")
                .doesNotContain("ru.locus.theory");
    }

    @Test
    void neitherDoesTheTreeRepository() {
        assertThat(sourceOf(TaxonomyRepository.class)).doesNotContain("ru.locus.theory");
    }

    /**
     * Подъём по предкам — операция дерева, и теория пользуется ею как
     * потребитель. Обратной ссылки это не создаёт: {@code ancestry} ничего
     * не знает о том, ради чего её позвали, и о материалах в том числе.
     */
    @Test
    void norDoesTheAncestryWalkTheoryLeansOn() {
        assertThat(sourceOf(TaxonomyService.class))
                .as("подъём по предкам заведён для теории, но написан без неё")
                .contains("ancestry")
                .doesNotContain("TheoryMaterial");
    }

    private static String sourceOf(Class<?> type) {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
