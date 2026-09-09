package ru.locus.taxonomy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Хранение дерева рубрикатора.
 *
 * Рубрикатор — общая библиотека: параметра владельца здесь нет и быть
 * не должно (ADR-0027). Передать его сюда некуда, поэтому «на всякий случай
 * отфильтровать» физически не получится, а фильтр, применённый к библиотеке,
 * сделал бы её невидимой.
 *
 * Иерархия хранится единственной связью «родитель», обход поддерева —
 * рекурсивным запросом. Материализованный путь и {@code ltree} отвергнуты:
 * оба требуют переписывать поддерево при каждом перемещении, и незавершённая
 * перезапись теряет узлы молча.
 *
 * Решений о том, что кому видно и что кому позволено, репозиторий
 * не содержит: правила дерева и права проверяет {@link TaxonomyService}.
 */
@Repository
public class TaxonomyRepository {

    /**
     * Предел глубины рекурсивного обхода.
     *
     * Настоящее дерево на порядки мельче. Предел стоит не ради ограничения
     * учителя, а ради того, чтобы циклическая ссылка — если она всё же
     * заведётся в обход проверки при перемещении — давала ошибку, а не
     * висящий запрос.
     */
    static final int MAX_DEPTH = 100;

    /**
     * Число прямых потомков читается вместе с узлом: вид узла (Раздел или
     * Тема) вычисляется из него и нигде не хранится.
     */
    private static final String CHILD_COUNT =
            "(select count(*) from taxonomy_node c where c.parent_id = n.id) as child_count";

    private final JdbcClient database;

    public TaxonomyRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Корневые узлы, по алфавиту.
     *
     * Отдельный метод с явным {@code is null}, а не подстановка {@code null}
     * в чтение потомков: {@code where parent_id = null} в SQL не ошибка,
     * а пустой ответ — список корней потерялся бы молча.
     */
    public List<TaxonomyNode> findRoots() {
        return database.sql("""
                        select n.id, n.name, n.parent_id, %s
                        from taxonomy_node n
                        where n.parent_id is null
                        order by n.name
                        """.formatted(CHILD_COUNT))
                .query(TaxonomyRepository::node)
                .list();
    }

    /** Прямые потомки узла, по алфавиту. */
    public List<TaxonomyNode> findChildren(TaxonomyNodeId parent) {
        return database.sql("""
                        select n.id, n.name, n.parent_id, %s
                        from taxonomy_node n
                        where n.parent_id = ?
                        order by n.name
                        """.formatted(CHILD_COUNT))
                .param(parent.value())
                .query(TaxonomyRepository::node)
                .list();
    }

    public Optional<TaxonomyNode> findById(TaxonomyNodeId id) {
        return database.sql("""
                        select n.id, n.name, n.parent_id, %s
                        from taxonomy_node n
                        where n.id = ?
                        """.formatted(CHILD_COUNT))
                .param(id.value())
                .query(TaxonomyRepository::node)
                .optional();
    }

    /**
     * Узел и все его потомки на любой глубине, включая сам запрошенный узел.
     *
     * На этом обходе держится правило «поиск по узлу включает всё поддерево»
     * (инвариант 10). Рекурсия ограничена {@link #MAX_DEPTH}: испорченные
     * данные должны давать ошибку, а не висящий запрос.
     *
     * @throws IllegalStateException если предел глубины достигнут — при
     *                               настоящем дереве это означает цикл
     */
    public List<TaxonomyNode> findSubtree(TaxonomyNodeId root) {
        List<Depth> depths = database.sql("""
                        with recursive subtree as (
                            select id, name, parent_id, 1 as depth
                            from taxonomy_node
                            where id = ?
                            union all
                            select child.id, child.name, child.parent_id, parent.depth + 1
                            from taxonomy_node child
                            join subtree parent on child.parent_id = parent.id
                            where parent.depth < ?
                        )
                        select n.id, n.name, n.parent_id, s.depth, %s
                        from subtree s
                        join taxonomy_node n on n.id = s.id
                        order by n.name
                        """.formatted(CHILD_COUNT))
                .params(root.value(), MAX_DEPTH)
                .query((rs, rowNum) -> new Depth(node(rs, rowNum), rs.getInt("depth")))
                .list();

        if (depths.stream().anyMatch(depth -> depth.depth() >= MAX_DEPTH)) {
            throw new IllegalStateException(
                    "Обход поддерева узла " + root.value() + " достиг предела глубины " + MAX_DEPTH
                            + ": похоже на цикл в дереве");
        }
        return depths.stream().map(Depth::node).toList();
    }

    public int countChildren(TaxonomyNodeId id) {
        return database.sql("select count(*) from taxonomy_node where parent_id = ?")
                .param(id.value())
                .query(Integer.class)
                .single();
    }

    /** Заводит узел и возвращает его идентификатор. {@code parent} null — корень. */
    public TaxonomyNodeId create(String name, TaxonomyNodeId parent) {
        Long id = database.sql("""
                        insert into taxonomy_node (name, parent_id)
                        values (?, ?)
                        returning id
                        """)
                .params(name, parent == null ? null : parent.value())
                .query(Long.class)
                .single();
        return new TaxonomyNodeId(id);
    }

    public void rename(TaxonomyNodeId id, String name) {
        database.sql("update taxonomy_node set name = ? where id = ?")
                .params(name, id.value())
                .update();
    }

    /** Меняет родителя узла. {@code newParent} null — узел поднимается в корень. */
    public void changeParent(TaxonomyNodeId id, TaxonomyNodeId newParent) {
        database.sql("update taxonomy_node set parent_id = ? where id = ?")
                .params(newParent == null ? null : newParent.value(), id.value())
                .update();
    }

    /**
     * Снимает узел.
     *
     * Каскада по внешнему ключу нет намеренно: удаление узла с потомками
     * должно падать, а не сносить поддерево одним запросом. Проверку пустоты
     * ставит {@link TaxonomyService}, внешний ключ — последний рубеж.
     */
    public void delete(TaxonomyNodeId id) {
        database.sql("delete from taxonomy_node where id = ?")
                .param(id.value())
                .update();
    }

    private static TaxonomyNode node(ResultSet rs, int rowNum) throws SQLException {
        long parent = rs.getLong("parent_id");
        // wasNull() относится к последнему прочитанному столбцу, поэтому
        // спрашивается сразу, а не в момент сборки записи.
        TaxonomyNodeId parentId = rs.wasNull() ? null : new TaxonomyNodeId(parent);
        return new TaxonomyNode(
                new TaxonomyNodeId(rs.getLong("id")),
                rs.getString("name"),
                parentId,
                rs.getInt("child_count"));
    }

    /** Узел вместе с глубиной, на которой его нашёл рекурсивный обход. */
    private record Depth(TaxonomyNode node, int depth) {
    }
}
