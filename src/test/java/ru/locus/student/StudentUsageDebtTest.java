package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Долг «Ученик удаляется, пока на него ничего не ссылается» — записан,
 * погашен и остаётся названным там, где его найдут.
 *
 * Условие живёт отдельным названным методом, чтобы каждое новое пополнение
 * дописывалось в одно место; пояснение к нему перечисляет пять работ,
 * которые обязаны были ответить на вопрос {@link StudentUsage}, — Задания
 * ({@code assignments}), Работы ({@code submission-review}), отметки
 * Владения ({@code mastery-marks}), Занятия ({@code schedule}) и Разминки
 * ({@code warmup}). Все пять ответили, и тест проверяет не «долг записан»,
 * а «долга нет и он не вернётся молча»: ни одна не значится должной,
 * и реализаций вопроса ровно пять.
 *
 * <p>Проверяется исходный текст, как и прежде: список работ в пояснении —
 * единственная подсказка о том, чем условие обязано быть полным, и убранная
 * при уборке кода строка компилируется так же хорошо, как и стоявшая.
 * Что каждый ответчик отвечает по существу, проверяют
 * {@code AssignmentsOfStudentTest}, {@code WorksOfStudentTest},
 * {@code MasteryOfStudentTest}, {@code LessonsOfStudentTest}
 * и {@code WarmupsOfStudentTest}.
 */
class StudentUsageDebtTest {

    private static final Path SOURCES = Path.of("src/main/java");

    @Test
    void theCheckIsANamedMethodAndNotACondition() throws IOException {
        assertThat(sourceOf(StudentService.class))
                .as("проверка ссылок — отдельный названный метод, а не условие внутри удаления")
                .contains("refuseUnlessUnused");
    }

    @Test
    void theCheckNamesAllFiveWorksAsAnswered() throws IOException {
        String source = sourceOf(StudentService.class);

        assertThat(source).contains("assignments").contains("submission-review").contains("mastery-marks")
                .contains("schedule").contains("warmup");
        assertThat(source)
                .as("ни одна из пяти работ больше не значится должной")
                .doesNotContain("должна");
    }

    /**
     * Условие пополняется ответом на вопрос, а не строкой внутри сервиса:
     * Задания, Работы, отметки, Занятия и Разминки — свои области, и {@code student} не должен
     * узнавать о них по имени. Исчезни вопрос — и пополнять станет некуда.
     */
    @Test
    void theQuestionItselfIsDeclaredAndNamesTheSameWorks() throws IOException {
        String question = sourceOf(StudentUsage.class);

        assertThat(sourceOf(StudentService.class))
                .as("проверка спрашивает StudentUsage")
                .contains("StudentUsage");
        assertThat(question).contains("assignments").contains("submission-review").contains("mastery-marks")
                .contains("schedule").contains("warmup");
        assertThat(question).doesNotContain("должна");
        assertThat(question)
                .as("пояснение называет ADR, где решение обосновано")
                .contains("ADR-0035");
    }

    /** Ответчиков ровно пять — по одному на каждую сущность, ссылающуюся на Ученика. */
    @Test
    void thereAreExactlyFiveAnswers() throws IOException {
        try (Stream<Path> tree = Files.walk(SOURCES)) {
            List<String> answers = tree.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains("implements StudentUsage"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(answers).containsExactly(
                    "AssignmentsOfStudent.java", "LessonsOfStudent.java", "MasteryOfStudent.java",
                    "WarmupsOfStudent.java", "WorksOfStudent.java");
        }
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = SOURCES.resolve(type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Не прочитать исходник " + path, unreadable);
        }
    }
}
