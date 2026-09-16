package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: набор значений вердикта взят из словаря, а не придуман
 * по месту.
 *
 * Проверяется именно набор, имена констант и русские названия: имя хранится
 * в базе строкой (колонка {@code student_work.verdict}), поэтому придуманное
 * здесь значение разошлось бы со словарём молча. Ровно два значения —
 * ADR-0014: вердикт бинарный, «не проверена» — отсутствие вердикта,
 * а не третья константа (ADR-0038).
 */
class VerdictTest {

    @Test
    void thereAreExactlyTwoVerdictsNamedAsTheGlossarySays() {
        assertThat(Verdict.values())
                .as("словарь называет ровно два вердикта: CORRECT и INCORRECT; «не проверена» — не вердикт")
                .containsExactly(Verdict.CORRECT, Verdict.INCORRECT);
    }

    @Test
    void namesAreStoredAsTheyAreWritten() {
        assertThat(Verdict.valueOf("CORRECT")).isEqualTo(Verdict.CORRECT);
        assertThat(Verdict.valueOf("INCORRECT")).isEqualTo(Verdict.INCORRECT);
    }

    @Test
    void everyVerdictIsTitledForThePersonAsTheGlossarySays() {
        assertThat(Verdict.CORRECT.title()).isEqualTo("верно");
        assertThat(Verdict.INCORRECT.title()).isEqualTo("неверно");
    }
}
