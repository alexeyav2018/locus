package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Задача 4.3: долг «Ученик удаляется, пока на него ничего не ссылается»
 * записан там, где его найдут.
 *
 * Сегодня условие выполняется тождественно — реализаций {@link StudentUsage}
 * нет ни одной: ни Заданий, ни Работ, ни отметок Владения в системе
 * не существует. Проверка живёт отдельным названным методом ровно для того,
 * чтобы будущее условие дописывалось в одно место; но проверка, никогда
 * не срабатывающая, при следующей уборке кода выглядит лишней и удаляется
 * молча — а вместе с ней уходит и единственная подсказка о том, что сюда
 * надо вернуться.
 *
 * Поэтому проверяется сам исходный текст: метод на месте и назван, а его
 * пояснение называет все три работы, обязанные условие пополнить. Долг
 * записан и требованием спеки, и ADR-0035 — здесь третий, самый близкий
 * к коду рубеж, устроенный по образцу {@code ProblemUsageDebtTest}.
 */
class StudentUsageDebtTest {

    @Test
    void theCheckIsANamedMethodAndNotACondition() throws IOException {
        assertThat(sourceOf(StudentService.class))
                .as("проверка ссылок — отдельный названный метод, а не условие внутри удаления")
                .contains("refuseUnlessUnused");
    }

    @Test
    void theCheckNamesAllThreeWorksThatMustExtendIt() throws IOException {
        String source = sourceOf(StudentService.class);

        assertThat(source)
                .as("Ученик с Заданием не удаляется — assignments обязан пополнить условие")
                .contains("assignments");
        assertThat(source)
                .as("Ученик с Работой не удаляется — submission-review обязан пополнить условие")
                .contains("submission-review");
        assertThat(source)
                .as("Ученик с отметкой Владения не удаляется — mastery-marks обязан пополнить условие")
                .contains("mastery-marks");
    }

    /**
     * Условие пополняется ответом на вопрос, а не строкой внутри сервиса:
     * Задания, Работы и отметки — свои области, и {@code student} не должен
     * узнавать о них по имени. Исчезни вопрос — и пополнять станет некуда.
     */
    @Test
    void theQuestionItselfIsDeclaredAndNamesTheSameWorks() throws IOException {
        String question = sourceOf(StudentUsage.class);

        assertThat(sourceOf(StudentService.class))
                .as("проверка спрашивает StudentUsage")
                .contains("StudentUsage");
        assertThat(question).contains("assignments").contains("submission-review").contains("mastery-marks");
        assertThat(question)
                .as("пояснение называет ADR, где решение обосновано")
                .contains("ADR-0035");
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
