package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Задача 6.2: долг «правка и удаление только до первого использования»
 * записан там, где его найдут.
 *
 * Проверка живёт отдельным названным методом, чтобы каждое новое условие
 * дописывалось в одно место; пояснение к нему перечисляет работы, чьи
 * сущности ссылаются на Задачу, — Задания ({@code assignments}), Работы
 * ({@code submission-review}, покрыты ответом Заданий) и Разминки
 * ({@code warmup}). Убранная при уборке кода строка пояснения компилируется
 * так же хорошо, как и стоявшая, поэтому проверяется сам исходный текст,
 * по образцу {@code DeletionCheckDebtTest}; а что ответчиков ровно столько,
 * сколько названо, — обходом исходников, как в {@code StudentUsageDebtTest}.
 * Долг записан и требованием спеки, и ADR-0030 — здесь третий, самый
 * близкий к коду рубеж.
 */
class ProblemUsageDebtTest {

    @Test
    void theCheckIsANamedMethodAndNotACondition() throws IOException {
        assertThat(sourceOf(ProblemService.class))
                .as("проверка использования — отдельный названный метод, а не условие внутри правки")
                .contains("refuseUnlessUnused");
    }

    @Test
    void theCheckNamesEveryWorkThatMustExtendIt() throws IOException {
        String source = sourceOf(ProblemService.class);

        assertThat(source)
                .as("Задача, вошедшая в Задание, замораживается — assignments обязан пополнить условие")
                .contains("assignments");
        assertThat(source)
                .as("Задача, имеющая Работу, замораживается — submission-review обязан пополнить условие")
                .contains("submission-review");
        assertThat(source)
                .as("Задача, вошедшая в Разминку, замораживается — warmup обязан пополнить условие")
                .contains("warmup");
    }

    /**
     * Условие пополняется ответом на вопрос, а не строкой внутри сервиса:
     * Задания, Работы и Разминки живут в личном контуре учителя, и библиотека не должна
     * узнавать о них по имени. Исчезни вопрос — и пополнять станет некуда.
     */
    @Test
    void theQuestionItselfIsDeclaredAndNamesTheSameWorks() throws IOException {
        String question = sourceOf(ProblemUsage.class);

        assertThat(sourceOf(ProblemService.class))
                .as("проверка спрашивает ProblemUsage")
                .contains("ProblemUsage");
        assertThat(question).contains("assignments").contains("submission-review").contains("warmup");
        assertThat(question)
                .as("пояснение называет ADR, где решение обосновано")
                .contains("ADR-0030");
    }

    /** Ответчиков ровно два — у Заданий и у Разминок; у Работы своего нет, она внутри Задания. */
    @Test
    void thereAreExactlyTwoAnswers() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("src/main/java"))) {
            List<String> answers = tree.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains("implements ProblemUsage"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(answers).containsExactly("AssignmentsOfProblem.java", "WarmupsOfProblem.java");
        }
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
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
