package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.2: набор значений Части взят из словаря, а не придуман по месту.
 *
 * Проверяется именно набор и имена констант: имя хранится в базе строкой
 * (колонка {@code problem.exam_part}), поэтому придуманное здесь значение
 * разошлось бы со словарём молча и пережило бы любое переименование
 * (glossary.md, «Перечисления»).
 */
class ExamPartTest {

    @Test
    void thereAreExactlyTwoPartsNamedAsTheGlossarySays() {
        assertThat(ExamPart.values())
                .as("словарь называет ровно две Части: FIRST и SECOND")
                .containsExactly(ExamPart.FIRST, ExamPart.SECOND);
    }

    @Test
    void namesAreStoredAsTheyAreWritten() {
        assertThat(ExamPart.valueOf("FIRST")).isEqualTo(ExamPart.FIRST);
        assertThat(ExamPart.valueOf("SECOND")).isEqualTo(ExamPart.SECOND);
    }

    @Test
    void everyPartHasARussianTitleForThePerson() {
        for (ExamPart part : ExamPart.values()) {
            assertThat(part.title()).as("Часть %s названа человеку по-русски", part).isNotBlank();
        }
    }
}
