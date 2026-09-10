package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Задача 1.1: набор способов соединения взят из словаря, а не придуман
 * по месту (glossary.md, «Перечисления»; ADR-0031).
 *
 * Проверяется именно набор и имена констант. В отличие от {@link ExamPart},
 * значение {@code MatchMode} в базе не хранится — оно приходит параметром
 * запроса, — но приходит по имени константы, и придуманное здесь имя
 * разошлось бы со словарём так же молча.
 */
class MatchModeTest {

    @Test
    void thereAreExactlyTwoModesNamedAsTheGlossarySays() {
        assertThat(MatchMode.values())
                .as("словарь называет ровно два способа: ALL и ANY")
                .containsExactly(MatchMode.ALL, MatchMode.ANY);
    }

    @Test
    void namesAreReadAsTheyAreWritten() {
        assertThat(MatchMode.valueOf("ALL")).isEqualTo(MatchMode.ALL);
        assertThat(MatchMode.valueOf("ANY")).isEqualTo(MatchMode.ANY);
    }

    @Test
    void everyModeHasARussianTitleForThePerson() {
        for (MatchMode mode : MatchMode.values()) {
            assertThat(mode.title()).as("способ %s назван человеку по-русски", mode).isNotBlank();
        }
    }
}
