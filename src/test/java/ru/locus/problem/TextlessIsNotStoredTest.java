package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestLibrary;

/**
 * «Без текста» не хранится — это отсутствие текста (design.md изменения
 * {@code problem-text}, Р3), того же рода, что «не сдано»
 * ({@link ru.locus.assignment.NotSubmittedIsNotStoredTest}) и «неизвестно»
 * у отметки Владения.
 *
 * Хранимый вид Задачи был бы вторым источником правды рядом с самим текстом:
 * запись текста Задаче без текста обязана была бы не забыть перевернуть
 * флаг, и забытая дала бы Задачу «без текста» с текстом — молча. Поэтому
 * проверяется отсутствие места, где такой признак мог бы завестись:
 * колонки в схеме и поля в записи, — и то, что база сама не пускает
 * половинчатое состояние, которое запись {@link ProblemText} не построит.
 */
class TextlessIsNotStoredTest extends IntegrationTest {

    /** Слова, которыми назвали бы хранимый вид Задачи. */
    private static final List<String> KIND_WORDS = List.of("kind", "type", "has_text", "textless", "with_text");

    @Autowired
    private JdbcClient database;

    @Autowired
    private TestLibrary library;

    @Test
    void schemaHasNoColumnForTheKindOfProblem() {
        List<Column> columns = database.sql("""
                        select column_name, data_type
                        from information_schema.columns
                        where table_name = 'problem'
                        """)
                .query((rs, rowNum) -> new Column(rs.getString("column_name"), rs.getString("data_type")))
                .list();

        assertThat(columns)
                .as("вид Задачи выводится из текста, а не лежит колонкой, которую надо переворачивать")
                .allSatisfy(column -> {
                    assertThat(column.type()).isNotEqualTo("boolean");
                    assertThat(KIND_WORDS).allSatisfy(word -> assertThat(column.name()).doesNotContain(word));
                });
    }

    @Test
    void problemHasNoFieldForTheKindOfProblem() {
        assertThat(Arrays.stream(Problem.class.getRecordComponents()))
                .as("«с текстом» — метод hasText(), а не поле рядом с текстом")
                .allSatisfy(component -> {
                    assertThat(component.getType()).isNotEqualTo(boolean.class);
                    assertThat(component.getType()).isNotEqualTo(Boolean.class);
                });
    }

    @Test
    void databaseRefusesOneTextWithoutTheOther() {
        long id = library.problem(library.topic()).value();

        assertThatThrownBy(() -> database.sql("update problem set condition_text = 'Найдите $x$.' where id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_problem_both_texts");
    }

    @Test
    void databaseRefusesEvidenceWithoutText() {
        long id = library.problem(library.topic()).value();

        assertThatThrownBy(() -> database.sql("update problem set solution_evidence_key = 'evidence.pdf' where id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_problem_evidence_with_text");
    }

    private record Column(String name, String type) {
    }
}
