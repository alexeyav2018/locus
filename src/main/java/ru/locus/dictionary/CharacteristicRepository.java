package ru.locus.dictionary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Хранение словаря Характеристик — симметрично
 * {@link SolutionMethodRepository} и по тем же причинам.
 *
 * Словарь — общая библиотека: параметра владельца здесь нет и быть не должно
 * (ADR-0027). Чтение списка отдаёт словарь целиком.
 */
@Repository
public class CharacteristicRepository {

    private final JdbcClient database;

    public CharacteristicRepository(JdbcClient database) {
        this.database = database;
    }

    /** Весь словарь, по алфавиту; порядок — по {@code lower(name)}. */
    public List<Characteristic> findAll() {
        return database.sql("select id, name from characteristic order by lower(name)")
                .query(CharacteristicRepository::characteristic)
                .list();
    }

    public Optional<Characteristic> findById(CharacteristicId id) {
        return database.sql("select id, name from characteristic where id = ?")
                .param(id.value())
                .query(CharacteristicRepository::characteristic)
                .optional();
    }

    /** Запись с таким именем, без учёта регистра — тем же сравнением, что и индекс. */
    public Optional<Characteristic> findByName(String name) {
        return database.sql("select id, name from characteristic where lower(name) = lower(?)")
                .param(name)
                .query(CharacteristicRepository::characteristic)
                .optional();
    }

    /** Заводит запись и возвращает её идентификатор. */
    public CharacteristicId create(String name) {
        Long id = database.sql("insert into characteristic (name) values (?) returning id")
                .param(name)
                .query(Long.class)
                .single();
        return new CharacteristicId(id);
    }

    public void rename(CharacteristicId id, String name) {
        database.sql("update characteristic set name = ? where id = ?")
                .params(name, id.value())
                .update();
    }

    public void delete(CharacteristicId id) {
        database.sql("delete from characteristic where id = ?")
                .param(id.value())
                .update();
    }

    private static Characteristic characteristic(ResultSet rs, int rowNum) throws SQLException {
        return new Characteristic(new CharacteristicId(rs.getLong("id")), rs.getString("name"));
    }
}
