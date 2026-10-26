package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Справка ячейки собирается в записи, а не в шаблоне (standards.md:
 * шаблоны не вычисляют), и называет оба основания порознь — Работы
 * и Разминки (ADR-0061); у основания без суждений — «0 из 0», а не ошибка
 * и не пропуск (ADR-0039).
 */
class MasteryCellTest {

    private static final TaxonomyNodeId TOPIC = new TaxonomyNodeId(7);
    private static final SolutionMethodId METHOD = new SolutionMethodId(3);

    @Test
    void hintNamesBothGroundsApart() {
        MasteryCell cell = new MasteryCell(TOPIC, "Алгебра / Уравнения", METHOD, "подстановка",
                MasteryStatus.UNKNOWN, new Solved(1, 1), new Solved(1, 2));

        assertThat(cell.hint())
                .as("основания не складываются в «2 из 3»: учитель взвешивает их сам (ADR-0061)")
                .isEqualTo("Работы: 1 из 1 · разминки: 1 из 2");
    }

    @Test
    void hintWithoutJudgementsStillNamesBothGrounds() {
        MasteryCell cell = new MasteryCell(TOPIC, "Алгебра / Уравнения", METHOD, "подстановка",
                MasteryStatus.UNKNOWN, Solved.NONE, Solved.NONE);

        assertThat(cell.hint())
                .as("непроверенные Работы и пустые клетки в справку не входят: без суждений — «0 из 0» (ADR-0039)")
                .isEqualTo("Работы: 0 из 0 · разминки: 0 из 0");
    }

    @Test
    void succeededCannotExceedJudged() {
        assertThatThrownBy(() -> new Solved(4, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothGroundsAreRequired() {
        assertThatThrownBy(() -> new MasteryCell(TOPIC, "Алгебра", METHOD, "подстановка",
                MasteryStatus.UNKNOWN, Solved.NONE, null))
                .as("пустое основание — Solved.NONE, а не null")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusIsRequiredAndAbsentJudgementIsUnknownNotNull() {
        assertThatThrownBy(() -> new MasteryCell(TOPIC, "Алгебра", METHOD, "подстановка", null, Solved.NONE, Solved.NONE))
                .as("ячейка без строки в базе — UNKNOWN, а не null: null перепутался бы с «без изменения» у Mark")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cellKeyIsThePair() {
        MasteryCell cell = new MasteryCell(TOPIC, "Алгебра", METHOD, "подстановка", MasteryStatus.MASTERED,
                new Solved(1, 1), Solved.NONE);

        assertThat(cell.cell()).isEqualTo(new Cell(TOPIC, METHOD));
    }
}
