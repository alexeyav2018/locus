package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Долг «пополнить проверку удаления» записан там, где его найдут.
 *
 * Проверка «запись не используется» живёт отдельным методом ровно для того,
 * чтобы каждое новое условие дописывалось в одно место. Метод, чьё тело
 * пусто, при следующей уборке кода выглядит лишним и удаляется молча,
 * а вместе с ним уходит и единственная подсказка о том, что сюда надо
 * вернуться. Поэтому проверяется сам исходный текст.
 *
 * <b>Часть долга погашена.</b> Разметка Задачи пришла с {@code problem-catalog}:
 * условие теперь настоящее, и его срабатывание проверяется отдельно
 * ({@code ProblemsGuardTheDictionariesTest}). Остаётся долг по отметкам
 * Владения — их ячейки опираются на Метод, и {@code mastery-marks} обязан
 * добавить свой ответ. Долг записан и требованием спеки, и карточкой
 * бэклога — здесь третий, самый близкий к коду рубеж.
 */
class DeletionCheckDebtTest {

    @Test
    void methodDeletionCheckNamesTheWorksThatMustExtendIt() throws IOException {
        String source = sourceOf(SolutionMethodService.class);

        assertThat(source)
                .as("проверка использования — отдельный названный метод, а не условие внутри удаления")
                .contains("refuseUnlessUnused");
        assertThat(source)
                .as("разметка Задачи пришла с problem-catalog — условие названо погашенным")
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
                .as("разметка Задачи пришла с problem-catalog")
                .contains("problem-catalog");
    }

    /**
     * Условие пополняется ответом на вопрос, а не строкой внутри сервиса:
     * следующая работа обязана добавить реализацию {@link DictionaryUsage},
     * ничего в словарях не правя. Исчезни вопрос — и пополнять станет некуда.
     */
    @Test
    void theCheckAsksTheQuestionInsteadOfKnowingTheAnswerItself() throws IOException {
        assertThat(sourceOf(SolutionMethodService.class))
                .as("проверка спрашивает DictionaryUsage")
                .contains("DictionaryUsage");
        assertThat(sourceOf(CharacteristicService.class)).contains("DictionaryUsage");
        assertThat(sourceOf(SolutionMethodService.class))
                .as("словарь не знает области Задач по имени")
                .doesNotContain("ru.locus.problem");
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
