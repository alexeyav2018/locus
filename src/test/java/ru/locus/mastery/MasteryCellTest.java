package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Задача 2.1: справка ячейки собирается в записи, а не в шаблоне
 * (standards.md: шаблоны не вычисляют), и считает только проверенные
 * Работы (ADR-0039) — потому «0 из 0» у пары без вердиктов, а не ошибка.
 */
class MasteryCellTest {

    private static final TaxonomyNodeId TOPIC = new TaxonomyNodeId(7);
    private static final SolutionMethodId METHOD = new SolutionMethodId(3);

    @Test
    void hintSaysHowManySolvedOfChecked() {
        MasteryCell cell = new MasteryCell(TOPIC, "Алгебра / Уравнения", METHOD, "подстановка",
                MasteryStatus.UNKNOWN, 2, 3);

        assertThat(cell.hint()).isEqualTo("решено 2 из 3");
    }

    @Test
    void hintForAPairWithoutCheckedWorksIsZeroOfZero() {
        MasteryCell cell = new MasteryCell(TOPIC, "Алгебра / Уравнения", METHOD, "подстановка",
                MasteryStatus.UNKNOWN, 0, 0);

        assertThat(cell.hint())
                .as("непроверенные Работы в справку не входят: без вердиктов — «0 из 0» (ADR-0039)")
                .isEqualTo("решено 0 из 0");
    }

    @Test
    void solvedCannotExceedChecked() {
        assertThatThrownBy(() -> new MasteryCell(TOPIC, "Алгебра", METHOD, "подстановка",
                MasteryStatus.UNKNOWN, 4, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusIsRequiredAndAbsentJudgementIsUnknownNotNull() {
        assertThatThrownBy(() -> new MasteryCell(TOPIC, "Алгебра", METHOD, "подстановка", null, 0, 0))
                .as("ячейка без строки в базе — UNKNOWN, а не null: null перепутался бы с «без изменения» у Mark")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cellKeyIsThePair() {
        MasteryCell cell = new MasteryCell(TOPIC, "Алгебра", METHOD, "подстановка", MasteryStatus.MASTERED, 1, 1);

        assertThat(cell.cell()).isEqualTo(new Cell(TOPIC, METHOD));
    }
}
