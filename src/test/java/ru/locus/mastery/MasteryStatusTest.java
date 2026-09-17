package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: шкала владения взята из словаря, а не придумана по месту.
 *
 * Проверяется именно набор, имена констант и русские названия: имя
 * хранится в базе строкой (колонка {@code mastery.status}), поэтому
 * придуманное здесь значение разошлось бы со словарём молча. Ровно
 * четыре значения — ADR-0012; из них {@code UNKNOWN} — единственное
 * без суждения, и в базу оно не пишется (ADR-0039).
 */
class MasteryStatusTest {

    @Test
    void thereAreExactlyFourStatusesNamedAsTheGlossarySays() {
        assertThat(MasteryStatus.values())
                .as("словарь называет ровно четыре значения шкалы в этом порядке (ADR-0012)")
                .containsExactly(MasteryStatus.UNKNOWN,
                        MasteryStatus.NOT_MASTERED,
                        MasteryStatus.UNCERTAIN,
                        MasteryStatus.MASTERED);
    }

    @Test
    void namesAreStoredAsTheyAreWritten() {
        assertThat(MasteryStatus.valueOf("UNKNOWN")).isEqualTo(MasteryStatus.UNKNOWN);
        assertThat(MasteryStatus.valueOf("NOT_MASTERED")).isEqualTo(MasteryStatus.NOT_MASTERED);
        assertThat(MasteryStatus.valueOf("UNCERTAIN")).isEqualTo(MasteryStatus.UNCERTAIN);
        assertThat(MasteryStatus.valueOf("MASTERED")).isEqualTo(MasteryStatus.MASTERED);
    }

    @Test
    void everyStatusIsTitledForThePersonAsTheGlossarySays() {
        assertThat(MasteryStatus.UNKNOWN.title()).isEqualTo("неизвестно");
        assertThat(MasteryStatus.NOT_MASTERED.title()).isEqualTo("не владеет");
        assertThat(MasteryStatus.UNCERTAIN.title()).isEqualTo("владеет неуверенно");
        assertThat(MasteryStatus.MASTERED.title()).isEqualTo("владеет");
    }

    @Test
    void onlyUnknownIsNotAJudgement() {
        assertThat(MasteryStatus.UNKNOWN.isJudgement())
                .as("«неизвестно» — отсутствие суждения, строкой не хранится (ADR-0039)")
                .isFalse();
        assertThat(MasteryStatus.NOT_MASTERED.isJudgement())
                .as("«не владеет» — суждение учителя, а не «неизвестно» (ADR-0012)")
                .isTrue();
        assertThat(MasteryStatus.UNCERTAIN.isJudgement()).isTrue();
        assertThat(MasteryStatus.MASTERED.isJudgement()).isTrue();
    }
}
