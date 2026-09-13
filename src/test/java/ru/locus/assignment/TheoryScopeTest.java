package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: набор значений охвата теории взят из словаря, а не придуман
 * по месту.
 *
 * Проверяется именно набор, имена констант и русские названия: имя
 * хранится в базе строкой (колонка {@code assignment.theory_scope}),
 * поэтому придуманное здесь значение разошлось бы со словарём молча
 * и пережило бы любое переименование (glossary.md, «Перечисления»;
 * ADR-0018 — ровно два охвата, плюс «без теории»).
 */
class TheoryScopeTest {

    @Test
    void thereAreExactlyThreeScopesNamedAsTheGlossarySays() {
        assertThat(TheoryScope.values())
                .as("словарь называет ровно три охвата: NONE, TOPICS и TOPICS_AND_SECTIONS")
                .containsExactly(TheoryScope.NONE, TheoryScope.TOPICS, TheoryScope.TOPICS_AND_SECTIONS);
    }

    @Test
    void namesAreStoredAsTheyAreWritten() {
        assertThat(TheoryScope.valueOf("NONE")).isEqualTo(TheoryScope.NONE);
        assertThat(TheoryScope.valueOf("TOPICS")).isEqualTo(TheoryScope.TOPICS);
        assertThat(TheoryScope.valueOf("TOPICS_AND_SECTIONS")).isEqualTo(TheoryScope.TOPICS_AND_SECTIONS);
    }

    @Test
    void everyScopeIsTitledForThePersonAsTheGlossarySays() {
        assertThat(TheoryScope.NONE.title()).isEqualTo("без теории");
        assertThat(TheoryScope.TOPICS.title()).isEqualTo("только Темы задач");
        assertThat(TheoryScope.TOPICS_AND_SECTIONS.title()).isEqualTo("вместе с Разделами");
    }
}
