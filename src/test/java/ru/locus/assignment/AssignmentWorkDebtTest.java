package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Задача 4.4: долг «Работа отвечает Заданию» записан там, где его найдут.
 *
 * Сегодня вопрос {@link AssignmentWork} остаётся без ответа — реализаций
 * ни одной, Работ в системе не существует, — и оба его следствия выполняются
 * тождественно: ни одно Задание не сдано, любое Задание удаляется. Оба
 * условия живут в сервисе отдельными названными методами, чтобы будущий
 * ответ подключался в одно место; но проверка, никогда не срабатывающая,
 * при следующей уборке кода выглядит лишней и удаляется молча — а с ней
 * уходит и единственная подсказка, что сюда надо вернуться.
 *
 * Поэтому проверяется сам исходный текст: методы на месте и названы,
 * а пояснения сервиса и вопроса называют работу, обязанную ответить,
 * и обе записи журнала, где решения обоснованы. Устроено по образцу
 * {@code ProblemUsageDebtTest}, с одним отличием: сервис назван путём,
 * а не {@code AssignmentService.class}, — тест написан прежде сервиса
 * (задача 4.4 идёт перед 5.2), и ссылка на класс уронила бы компиляцию
 * всех тестов, а не один этот.
 */
class AssignmentWorkDebtTest {

    private static final Path SERVICE = Path.of("src/main/java/ru/locus/assignment/AssignmentService.java");

    @Test
    void theRefusalIsANamedMethodAndNotACondition() throws IOException {
        assertThat(sourceOf(SERVICE))
                .as("отказ в удалении Задания с Работой — отдельный названный метод, а не условие внутри удаления")
                .contains("refuseUnlessNoWork");
    }

    @Test
    void notSubmittedAsksTheQuestion() throws IOException {
        String source = sourceOf(SERVICE);

        assertThat(source)
                .as("«не сдано» вычисляется, а не хранится, и спрашивает AssignmentWork (инвариант 12, ADR-0016)")
                .contains("AssignmentWork")
                .contains("withWork");
    }

    @Test
    void theServiceNamesTheWorkThatMustAnswer() throws IOException {
        String source = sourceOf(SERVICE);

        assertThat(source)
                .as("Работа — submission-review — обязана ответить на вопрос")
                .contains("submission-review");
        assertThat(source)
                .as("пояснение называет ADR, где обоснованы удаление и «не сдано»")
                .contains("ADR-0037")
                .contains("ADR-0016");
    }

    /**
     * Ответ приходит реализацией вопроса, а не строкой внутри сервиса:
     * Работа живёт в своей области и Задания о ней по имени не знают.
     * Исчезни вопрос — и отвечать станет некуда.
     */
    @Test
    void theQuestionItselfIsDeclaredAndNamesTheSameWork() throws IOException {
        String question = sourceOf(sourceOf(AssignmentWork.class));

        assertThat(question).contains("submission-review");
        assertThat(question)
                .as("пояснение вопроса называет оба употребления ответа")
                .contains("ADR-0037")
                .contains("ADR-0016");
    }

    private static Path sourceOf(Class<?> type) {
        return Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
    }

    private static String sourceOf(Path source) throws IOException {
        assertThat(source).as("исходный текст %s найден", source.getFileName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
