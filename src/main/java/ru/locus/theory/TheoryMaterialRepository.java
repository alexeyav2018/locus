package ru.locus.theory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Хранение Теоретических материалов.
 *
 * Теория — общая библиотека: параметра владельца здесь нет и быть не должно
 * (ADR-0027). Передать его сюда некуда, поэтому «на всякий случай
 * отфильтровать» физически не получится, а фильтр, применённый к библиотеке,
 * сделал бы её невидимой.
 *
 * Наследование материалов вниз по дереву здесь не живёт: подъём по предкам —
 * дело рубрикатора, и сюда приходит уже готовый набор узлов
 * ({@link #findByNodes}). Второй обход дерева в SQL завёл бы второй предел
 * глубины, и разошлись бы они молча — стандарт проекта требует одного обхода
 * на весь проект (standards.md, «Данные»).
 *
 * Правил материала и проверок прав репозиторий не содержит: их ставит
 * сервис теории.
 */
@Repository
public class TheoryMaterialRepository {

    private final JdbcClient database;

    public TheoryMaterialRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Заводит материал и возвращает его идентификатор.
     *
     * Содержимое приходит готовым: файл кладётся в хранилище до записи, потому
     * что материал без файла выглядел бы настоящим, а забытый в хранилище файл
     * не виден никому (design.md, «Файл кладётся по общему правилу порядка»).
     *
     * Проверку «ровно одно из двух» ставят и запись {@link TheoryMaterial},
     * и проверочное ограничение схемы; здесь она не повторяется третий раз.
     */
    public TheoryMaterialId create(String title, TaxonomyNodeId node, FileKey file, String link) {
        Long id = database.sql("""
                        insert into theory_material (title, node_id, file_key, link)
                        values (?, ?, ?, ?)
                        returning id
                        """)
                .params(title, node.value(), file == null ? null : file.value(), link)
                .query(Long.class)
                .single();
        return new TheoryMaterialId(id);
    }

    public Optional<TheoryMaterial> findById(TheoryMaterialId id) {
        return database.sql("""
                        select id, title, node_id, file_key, link
                        from theory_material
                        where id = ?
                        """)
                .param(id.value())
                .query(TheoryMaterialRepository::material)
                .optional();
    }

    /**
     * Материалы перечисленных узлов — одним запросом на весь набор.
     *
     * Набор приходит от рубрикатора: это узел и все его предки до корня.
     * Порядок здесь — только по названию; раскладку по узлам, в которой свои
     * идут первыми, а дальше предки от ближайшего к корню, делает сервис:
     * порядок узлов известен ему, а не запросу.
     *
     * @throws IllegalArgumentException если набор пуст — подъём по предкам
     *                                  всегда содержит сам узел, значит вызов
     *                                  испорчен, а пустой ответ на него
     *                                  выглядел бы правдоподобно
     */
    public List<TheoryMaterial> findByNodes(List<TaxonomyNodeId> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Пустой набор узлов: подъём по предкам всегда содержит сам узел, значит вызов испорчен");
        }
        List<Object> parameters = new ArrayList<>(nodes.stream().map(TaxonomyNodeId::value).toList());
        return database.sql("""
                        select id, title, node_id, file_key, link
                        from theory_material
                        where node_id in (%s)
                        order by lower(title), id
                        """.formatted(placeholders(nodes.size())))
                .params(parameters)
                .query(TheoryMaterialRepository::material)
                .list();
    }

    /**
     * Сколько материалов лежит <b>на самом</b> узле — для проверок дерева.
     *
     * Унаследованные не считаются: они лежат не здесь и с удалением этого узла
     * ничего не теряют (ADR-0032).
     */
    public int countByNode(TaxonomyNodeId node) {
        return database.sql("select count(*) from theory_material where node_id = ?")
                .param(node.value())
                .query(Integer.class)
                .single();
    }

    public void changeTitle(TheoryMaterialId id, String title) {
        database.sql("update theory_material set title = ? where id = ?")
                .params(title, id.value())
                .update();
    }

    /** Переносит материал на другой узел: наследование меняется само собой. */
    public void changeNode(TheoryMaterialId id, TaxonomyNodeId node) {
        database.sql("update theory_material set node_id = ? where id = ?")
                .params(node.value(), id.value())
                .update();
    }

    /**
     * Заменяет содержимое целиком — обе колонки одним запросом.
     *
     * Двумя запросами («записать новое, затем стереть прежнее») эта правка
     * невозможна: между ними материал был бы с двумя содержимыми сразу,
     * а проверочное ограничение схемы такого состояния не допускает — первый
     * же запрос и упал бы. Замена файла ссылкой и наоборот поэтому только
     * одним запросом.
     */
    public void replaceContent(TheoryMaterialId id, FileKey file, String link) {
        database.sql("update theory_material set file_key = ?, link = ? where id = ?")
                .params(file == null ? null : file.value(), link, id.value())
                .update();
    }

    /**
     * Снимает материал.
     *
     * Файл из хранилища убирает сервис, после того как записи не стало:
     * запись без файла выглядит настоящей, а файл, на который никто
     * не ссылается, не мешает никому (standards.md, «Файлы»).
     */
    public void delete(TheoryMaterialId id) {
        database.sql("delete from theory_material where id = ?")
                .param(id.value())
                .update();
    }

    /**
     * Значения уходят параметрами; в текст запроса подставляется только
     * число вопросительных знаков.
     */
    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static TheoryMaterial material(ResultSet rs, int rowNum) throws SQLException {
        String file = rs.getString("file_key");
        return new TheoryMaterial(
                new TheoryMaterialId(rs.getLong("id")),
                rs.getString("title"),
                new TaxonomyNodeId(rs.getLong("node_id")),
                file == null ? null : new FileKey(file),
                rs.getString("link"));
    }
}
