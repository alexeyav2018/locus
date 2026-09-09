package ru.locus.dictionary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Хранение словаря Методов.
 *
 * Словарь — общая библиотека: параметра владельца здесь нет и быть не должно
 * (ADR-0027). Передать его сюда некуда, поэтому «на всякий случай
 * отфильтровать» физически не получится, а фильтр, применённый к библиотеке,
 * сделал бы её невидимой, а разметку — невозможной.
 *
 * Чтение списка отдаёт словарь целиком: выборочного чтения здесь нет ни
 * одного метода, и это проверяется отдельно.
 *
 * Общего параметризованного репозитория на оба словаря нет намеренно: имя
 * таблицы, подставленное в строку запроса, убивает всё, ради чего написан
 * типизированный идентификатор, и делает любую опечатку ошибкой времени
 * выполнения. Два коротких репозитория дешевле одного умного.
 *
 * Правил словаря и проверок прав репозиторий не содержит: их ставит
 * {@link SolutionMethodService}.
 */
@Repository
public class SolutionMethodRepository {

    private final JdbcClient database;

    public SolutionMethodRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Весь словарь, по алфавиту.
     *
     * Порядок задаётся {@code lower(name)}, а не {@code name}: имена
     * уникальны без учёта регистра, и порядок, зависящий от регистра, ставил
     * бы «Разложение» и «разложение» в разные концы списка на одной сортировке
     * и рядом на другой.
     */
    public List<SolutionMethod> findAll() {
        return database.sql("select id, name from solution_method order by lower(name)")
                .query(SolutionMethodRepository::method)
                .list();
    }

    public Optional<SolutionMethod> findById(SolutionMethodId id) {
        return database.sql("select id, name from solution_method where id = ?")
                .param(id.value())
                .query(SolutionMethodRepository::method)
                .optional();
    }

    /**
     * Запись с таким именем, без учёта регистра.
     *
     * Сравнение то же самое, что держит уникальный индекс: проверка в сервисе
     * и последний рубеж в схеме должны отказывать на одних и тех же именах,
     * иначе сервис пропустит то, на чём упадёт база.
     */
    public Optional<SolutionMethod> findByName(String name) {
        return database.sql("select id, name from solution_method where lower(name) = lower(?)")
                .param(name)
                .query(SolutionMethodRepository::method)
                .optional();
    }

    /** Заводит запись и возвращает её идентификатор. */
    public SolutionMethodId create(String name) {
        Long id = database.sql("insert into solution_method (name) values (?) returning id")
                .param(name)
                .query(Long.class)
                .single();
        return new SolutionMethodId(id);
    }

    public void rename(SolutionMethodId id, String name) {
        database.sql("update solution_method set name = ? where id = ?")
                .params(name, id.value())
                .update();
    }

    public void delete(SolutionMethodId id) {
        database.sql("delete from solution_method where id = ?")
                .param(id.value())
                .update();
    }

    private static SolutionMethod method(ResultSet rs, int rowNum) throws SQLException {
        return new SolutionMethod(new SolutionMethodId(rs.getLong("id")), rs.getString("name"));
    }
}
