package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.user.Role;

/**
 * Требование «Вид узла определяется наличием потомков, а не хранится».
 *
 * Хранимый признак был бы вторым источником правды о том, лист ли узел,
 * и разошёлся бы с первым молча — в тот момент, когда у Темы появляется
 * потомок. Поэтому проверяется не только поведение, но и отсутствие места,
 * где такой признак мог бы завестись: колонки в схеме и поля в записи узла.
 */
class NodeKindIsNotStoredTest extends IntegrationTest {

    /** Колонки таблицы узла — и никаких других. */
    private static final List<String> COLUMNS = List.of("id", "name", "parent_id");

    /** Слова, которыми назвали бы хранимый вид узла. */
    private static final List<String> KIND_WORDS = List.of("kind", "type", "section", "topic", "leaf");

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private JdbcClient database;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    @Test
    void schemaHasNoColumnForTheKindOfNode() {
        List<String> columns = database.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'taxonomy_node'
                        """)
                .query(String.class)
                .list();

        assertThat(columns)
                .as("в таблице узла ровно три колонки, и вида среди них нет")
                .containsExactlyInAnyOrderElementsOf(COLUMNS);
    }

    @Test
    void nodeHasNoFieldForTheKindOfNode() {
        List<String> components = Arrays.stream(TaxonomyNode.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .toList();

        assertThat(components)
                .as("вид узла вычисляется методами, а не лежит полем, которое надо обновлять")
                .allSatisfy(name -> assertThat(KIND_WORDS)
                        .allSatisfy(word -> assertThat(name).doesNotContain(word)));
    }

    /** Сценарий «Тема становится Разделом при появлении потомка». */
    @Test
    void topicBecomesASectionWhenAChildAppears() {
        TaxonomyNodeId node = taxonomy.create(unique("Алгебра"), null);
        assertThat(taxonomy.node(node).isTopic()).isTrue();

        TaxonomyNodeId child = taxonomy.create("Уравнения", node);

        assertThat(taxonomy.node(node).isSection()).isTrue();
        assertThat(taxonomy.node(child).isTopic()).as("созданный потомок — Тема").isTrue();
    }

    /** Сценарий «Раздел становится Темой, когда последний потомок ушёл». */
    @Test
    void sectionBecomesATopicWhenItsLastChildMovesAway() {
        TaxonomyNodeId former = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId other = taxonomy.create(unique("Геометрия"), null);
        TaxonomyNodeId onlyChild = taxonomy.create("Уравнения", former);
        assertThat(taxonomy.node(former).isSection()).isTrue();

        taxonomy.move(onlyChild, other);

        assertThat(taxonomy.node(former).isTopic()).isTrue();
        assertThat(taxonomy.node(other).isSection()).isTrue();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
