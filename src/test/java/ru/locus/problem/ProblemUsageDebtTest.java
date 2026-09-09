package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Задача 6.2: долг «правка и удаление только до первого использования»
 * записан там, где его найдут.
 *
 * Сегодня условие выполняется тождественно — реализаций {@link ProblemUsage}
 * нет ни одной, ни Заданий, ни Работ в системе не существует. Проверка живёт
 * отдельным названным методом ровно для того, чтобы будущее условие
 * дописывалось в одно место; но проверка, никогда не срабатывающая, при
 * следующей уборке кода выглядит лишней и удаляется молча — а вместе с ней
 * уходит и единственная подсказка о том, что сюда надо вернуться.
 *
 * Поэтому проверяется сам исходный текст: метод на месте и назван, а его
 * пояснение называет обе работы, обязанные условие пополнить. Долг записан
 * и требованием спеки, и ADR-0030 — здесь третий, самый близкий к коду
 * рубеж, устроенный по образцу {@code DeletionCheckDebtTest}.
 */
class ProblemUsageDebtTest {

    @Test
    void theCheckIsANamedMethodAndNotACondition() throws IOException {
        assertThat(sourceOf(ProblemService.class))
                .as("проверка использования — отдельный названный метод, а не условие внутри правки")
                .contains("refuseUnlessUnused");
    }

    @Test
    void theCheckNamesBothWorksThatMustExtendIt() throws IOException {
        String source = sourceOf(ProblemService.class);

        assertThat(source)
                .as("Задача, вошедшая в Задание, замораживается — assignments обязан пополнить условие")
                .contains("assignments");
        assertThat(source)
                .as("Задача, имеющая Работу, замораживается — submission-review обязан пополнить условие")
                .contains("submission-review");
    }

    /**
     * Условие пополняется ответом на вопрос, а не строкой внутри сервиса:
     * Задания и Работы живут в личном контуре учителя, и библиотека не должна
     * узнавать о них по имени. Исчезни вопрос — и пополнять станет некуда.
     */
    @Test
    void theQuestionItselfIsDeclaredAndNamesTheSameWorks() throws IOException {
        String question = sourceOf(ProblemUsage.class);

        assertThat(sourceOf(ProblemService.class))
                .as("проверка спрашивает ProblemUsage")
                .contains("ProblemUsage");
        assertThat(question).contains("assignments").contains("submission-review");
        assertThat(question)
                .as("пояснение называет ADR, где решение обосновано")
                .contains("ADR-0030");
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
