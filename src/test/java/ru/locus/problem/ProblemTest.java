package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Требование «Задача состоит из двух PDF и разметки» и требование «Разметка
 * Задачи — Темы, Методы, Характеристики и Часть» — та их часть, что проверяется
 * составом самой записи.
 *
 * Проверки стоят в компактном конструкторе, поэтому и проверяются на нём:
 * запись, собранная в обход правил, не должна существовать вообще — ни
 * в памяти, ни тем более в базе.
 */
class ProblemTest {

    private static final ProblemId ID = new ProblemId(12);
    private static final FileKey CONDITION = new FileKey("condition.pdf");
    private static final FileKey SOLUTION = new FileKey("solution.pdf");
    private static final List<TaxonomyNodeId> TOPICS = List.of(new TaxonomyNodeId(1));
    private static final List<SolutionMethodId> METHODS = List.of(new SolutionMethodId(1));

    /** Сценарий «Разметка несколькими Темами и Методами». */
    @Test
    void problemKeepsEveryMarkupItWasGiven() {
        Problem problem = new Problem(ID, "Ященко, вариант 12", ExamPart.SECOND, CONDITION, SOLUTION,
                List.of(new TaxonomyNodeId(1), new TaxonomyNodeId(2)),
                List.of(new SolutionMethodId(1), new SolutionMethodId(2), new SolutionMethodId(3)),
                List.of(new CharacteristicId(1)));

        assertThat(problem.topics()).hasSize(2);
        assertThat(problem.methods()).hasSize(3);
        assertThat(problem.characteristics()).hasSize(1);
    }

    /** Сценарий «Задача без Темы». */
    @Test
    void problemWithoutATopicIsNotBuilt() {
        assertThatThrownBy(() -> problemWith(List.of(), METHODS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна Тема");
    }

    /** Сценарий «Задача без Метода». */
    @Test
    void problemWithoutAMethodIsNotBuilt() {
        assertThatThrownBy(() -> problemWith(TOPICS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы один Метод");
    }

    /** Сценарий «Часть указывается ровно одна» — не указанная вовсе отклоняется. */
    @Test
    void problemWithoutAnExamPartIsNotBuilt() {
        assertThatThrownBy(() -> new Problem(ID, null, null, CONDITION, SOLUTION,
                TOPICS, METHODS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Часть");
    }

    /** Сценарий «Нет PDF условия». */
    @Test
    void problemWithoutTheConditionFileIsNotBuilt() {
        assertThatThrownBy(() -> new Problem(ID, null, ExamPart.FIRST, null, SOLUTION,
                TOPICS, METHODS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("условия");
    }

    /** Сценарий «Нет PDF решения». */
    @Test
    void problemWithoutTheSolutionFileIsNotBuilt() {
        assertThatThrownBy(() -> new Problem(ID, null, ExamPart.FIRST, CONDITION, null,
                TOPICS, METHODS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("решения");
    }

    /** Сценарий «Характеристики необязательны». */
    @Test
    void problemWithoutCharacteristicsIsBuilt() {
        Problem problem = new Problem(ID, null, ExamPart.FIRST, CONDITION, SOLUTION,
                TOPICS, METHODS, List.of());

        assertThat(problem.characteristics()).isEmpty();
    }

    /** Сценарий «Задача без подписи»: пустая подпись — это её отсутствие. */
    @Test
    void blankCaptionIsTheAbsenceOfACaption() {
        Problem problem = new Problem(ID, "   ", ExamPart.FIRST, CONDITION, SOLUTION,
                TOPICS, METHODS, List.of());

        assertThat(problem.hasCaption())
                .as("подпись из одних пробелов выглядела бы в списке пустой строкой")
                .isFalse();
        assertThat(problem.caption()).isNull();
        assertThat(problem.number()).as("тогда Задача различима номером").isEqualTo(12);
    }

    @Test
    void captionIsTrimmed() {
        Problem problem = new Problem(ID, "  Ященко  ", ExamPart.FIRST, CONDITION, SOLUTION,
                TOPICS, METHODS, List.of());

        assertThat(problem.caption()).isEqualTo("Ященко");
    }

    private static Problem problemWith(List<TaxonomyNodeId> topics, List<SolutionMethodId> methods) {
        return new Problem(ID, null, ExamPart.FIRST, CONDITION, SOLUTION, topics, methods, List.of());
    }
}
