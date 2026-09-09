package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Задача 3.5: долг «пополнить проверку удаления» записан там, где его найдут.
 *
 * Сегодня условие «запись не используется» выполняется тождественно —
 * ссылаться на запись нечему. Проверка существует отдельным методом ровно
 * для того, чтобы будущее условие дописывалось в одно место; но метод, чьё
 * тело пусто, при следующей уборке кода выглядит лишним и удаляется молча,
 * а вместе с ним уходит и единственная подсказка о том, что сюда надо
 * вернуться.
 *
 * Поэтому проверяется сам исходный текст: метод на месте и назван, а его
 * пояснение называет работы, с которыми условие обязано пополниться. Долг
 * записан и требованием спеки, и карточкой бэклога — здесь третий, самый
 * близкий к коду рубеж.
 */
class DeletionCheckDebtTest {

    @Test
    void methodDeletionCheckNamesTheWorksThatMustExtendIt() throws IOException {
        String source = sourceOf(SolutionMethodService.class);

        assertThat(source)
                .as("проверка использования — отдельный названный метод, а не условие внутри удаления")
                .contains("refuseUnlessUnused");
        assertThat(source)
                .as("разметка Задачи придёт с problem-catalog и обязана пополнить условие")
                .contains("problem-catalog");
        assertThat(source)
                .as("ячейки владения опираются на Метод — mastery-marks обязан пополнить условие")
                .contains("mastery-marks");
    }

    @Test
    void characteristicDeletionCheckNamesTheWorkThatMustExtendIt() throws IOException {
        String source = sourceOf(CharacteristicService.class);

        assertThat(source).contains("refuseUnlessUnused");
        assertThat(source)
                .as("разметка Задачи придёт с problem-catalog")
                .contains("problem-catalog");
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
