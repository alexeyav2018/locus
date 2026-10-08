package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;

/**
 * Подписи у Задачи нет — ни в записи, ни в схеме (ADR-0050, заменяет
 * ADR-0029): Задача различается номером, то есть своим идентификатором.
 *
 * Состав колонок перечислен ПОИМЁННО, как у
 * {@link ru.locus.assignment.NotSubmittedIsNotStoredTest}: колонка, которую
 * никто не заполняет и не показывает, говорила бы читающему схему, что
 * Подпись у Задачи есть. Вернувшая её миграция уронит этот тест, и автору
 * придётся объяснить, какое решение заменило ADR-0050.
 *
 * Признак готовности карточки бэклога: колонки {@code caption} в схеме нет.
 */
class ProblemHasNoCaptionTest extends IntegrationTest {

    @Autowired
    private JdbcClient database;

    @Test
    void theProblemTableCarriesNoCaption() {
        assertThat(database.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'problem'
                        """)
                .query(String.class)
                .list())
                .containsExactlyInAnyOrder("id", "exam_part", "condition_file_key", "solution_file_key");
    }
}
