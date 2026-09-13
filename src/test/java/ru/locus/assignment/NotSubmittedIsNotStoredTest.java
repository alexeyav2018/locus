package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;

/**
 * «Не сдано» не хранится — оно вычисляется (инвариант 12 domain-model.md,
 * ADR-0016): это выражение «срок прошёл и Работы нет», и сервис выводит
 * его при каждом показе из срока Задания, часов и ответа области Работ.
 *
 * Хранимый флаг пришлось бы кому-то проставлять и снимать, а забытое
 * действие дало бы Задание, которое числится сданным без Работы или
 * несданным при ней, — и обнаружилось бы это только по жалобе. Поэтому
 * состав колонок трёх таблиц области перечислен здесь ПОИМЁННО, как
 * у {@link ru.locus.student.StudentIsNotAUserTest}: миграция, добавившая
 * колонку «сдано», «просрочено» или дату сдачи, уронит этот тест, и
 * автору придётся объяснить, почему инвариант больше не держится.
 *
 * Признак готовности карточки бэклога: «не сдано» нигде не хранится.
 */
class NotSubmittedIsNotStoredTest extends IntegrationTest {

    @Autowired
    private JdbcClient database;

    /** Раздача помнит происхождение — имя Группы и дату, — и ничего о сдаче. */
    @Test
    void theBatchTableCarriesOnlyItsOrigin() {
        assertThat(columnsOf("assignment_batch"))
                .containsExactlyInAnyOrder("id", "user_id", "group_name", "issued_on");
    }

    /** У Задания есть срок, по которому «не сдано» вычисляется, — и нет ничего, где оно хранилось бы. */
    @Test
    void theAssignmentTableCarriesTheDueDateButNoVerdict() {
        assertThat(columnsOf("assignment"))
                .containsExactlyInAnyOrder(
                        "id", "user_id", "student_id", "assignment_batch_id", "issued_on", "due_date", "theory_scope");
    }

    /** Состав — какая Задача на каком месте; сдана ли она — дело Работы, не состава. */
    @Test
    void theCompositionTableCarriesOnlyTheOrder() {
        assertThat(columnsOf("assignment_problem"))
                .containsExactlyInAnyOrder("assignment_id", "problem_id", "user_id", "position");
    }

    private List<String> columnsOf(String table) {
        return database.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = ?
                        """)
                .param(table)
                .query(String.class)
                .list();
    }
}
