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
 * <b>Долг погашен целиком.</b> Разметка Задачи пришла
 * с {@code problem-catalog}, отметки Владения — с {@code mastery-marks};
 * условие настоящее с обеих сторон, и его срабатывание проверяется отдельно
 * ({@code ProblemsGuardTheDictionariesTest},
 * {@code MasteryGuardsTheMethodTest}). Тест остаётся: пояснение к методу —
 * единственная подсказка о том, чем условие обязано быть полным, и убранная
 * при уборке строка компилируется так же хорошо, как и стоявшая. Проверяется,
 * что обе работы названы и ни одна не значится должной.
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
                .as("ячейки владения опираются на Метод — mastery-marks ответила")
                .contains("mastery-marks");
        assertThat(source)
                .as("ни одна из двух работ не значится должной")
                .doesNotContain("обязан");
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
     * обе работы добавили реализацию {@link DictionaryUsage}, ничего
     * в словарях не правя. Исчезни вопрос — и пополнять станет некуда.
     */
    @Test
    void theCheckAsksTheQuestionInsteadOfKnowingTheAnswerItself() throws IOException {
        assertThat(sourceOf(SolutionMethodService.class))
                .as("проверка спрашивает DictionaryUsage")
                .contains("DictionaryUsage");
        assertThat(sourceOf(CharacteristicService.class)).contains("DictionaryUsage");
        assertThat(sourceOf(SolutionMethodService.class))
                .as("словарь не знает по имени ни области Задач, ни области отметок")
                .doesNotContain("ru.locus.problem")
                .doesNotContain("ru.locus.mastery");
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
