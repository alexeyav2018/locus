package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Задача 3.1 problem-intake: пометки {@code ⟦…⟧} снимаются, оставляя
 * фрагмент, и находятся с контекстом (design.md, Р5).
 */
class DoubtMarksTest {

    @Test
    void stripInsideAFormulaLeavesTheFormulaValid() {
        assertThat(DoubtMarks.strip("$\\log_2 ⟦8⟧ = 3$")).isEqualTo("$\\log_2 8 = 3$");
    }

    @Test
    void fragmentInsideAFormulaComesWithItsContext() {
        assertThat(DoubtMarks.fragments("$\\log_2 ⟦8⟧ = 3$"))
                .containsExactly(new DoubtMarks.Fragment("$\\log_2 ", "8", " = 3$"));
    }

    @Test
    void severalMarksGiveSeveralFragmentsAndTheirContextHasNoMarks() {
        assertThat(DoubtMarks.fragments("⟦a⟧ и ⟦b⟧"))
                .containsExactly(
                        new DoubtMarks.Fragment("", "a", " и b"),
                        new DoubtMarks.Fragment("a и ", "b", ""));
        assertThat(DoubtMarks.strip("⟦a⟧ и ⟦b⟧")).isEqualTo("a и b");
    }

    @Test
    void unpairedMarkIsStrippedButGivesNoFragment() {
        assertThat(DoubtMarks.strip("x ⟦ y")).isEqualTo("x  y");
        assertThat(DoubtMarks.fragments("x ⟦ y")).isEmpty();
        assertThat(DoubtMarks.strip("x ⟧ y")).isEqualTo("x  y");
        assertThat(DoubtMarks.fragments("x ⟧ y")).isEmpty();
    }

    @Test
    void openMarkInsideAnOpenMarkIsJustAMark() {
        assertThat(DoubtMarks.fragments("⟦a⟦b⟧c⟧"))
                .containsExactly(new DoubtMarks.Fragment("", "ab", "c"));
    }

    @Test
    void longContextIsCutWithAnEllipsis() {
        String side = "x".repeat(DoubtMarks.CONTEXT + 10);
        DoubtMarks.Fragment fragment = DoubtMarks.fragments(side + "⟦8⟧" + side).getFirst();

        assertThat(fragment.before()).isEqualTo("…" + "x".repeat(DoubtMarks.CONTEXT));
        assertThat(fragment.after()).isEqualTo("x".repeat(DoubtMarks.CONTEXT) + "…");
    }

    @Test
    void textWithoutMarksAndNothingAtAll() {
        assertThat(DoubtMarks.fragments("просто текст")).isEmpty();
        assertThat(DoubtMarks.fragments(null)).isEmpty();
        assertThat(DoubtMarks.strip(null)).isEmpty();
    }
}
