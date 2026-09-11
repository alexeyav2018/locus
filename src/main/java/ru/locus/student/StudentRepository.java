package ru.locus.student;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.user.UserId;

/**
 * Хранение Учеников — первый репозиторий ЛИЧНОГО контура.
 *
 * У каждого метода есть параметр владельца {@link UserId}, и в каждом SQL
 * стоит {@code user_id = ?}. Так требует ADR-0027: граница общего и личного
 * держится не дисциплиной «не забыть отфильтровать», а формой — владельца
 * невозможно не передать, потому что без него метод не вызвать. Забытый
 * фильтр на личных данных не падает, а тихо показывает учителю чужих
 * учеников; параметр в сигнатуре ловит это компилятором, а не глазами.
 *
 * Проверка стоит в самом запросе, а не «прочитать и сравнить владельца»
 * после: чтение чужой записи возвращает пусто, правка чужой — не меняет
 * ни одной строки. Для вызывающего чужая запись неотличима от
 * несуществующей, и так и должно быть: различие выдало бы существование
 * чужой карточки.
 *
 * Это зеркало библиотечных репозиториев (например,
 * {@link ru.locus.dictionary.SolutionMethodRepository}), где владельца
 * нет и быть не должно; форму стережёт {@code OwnerIsRequiredByStudentsTest},
 * поведение — {@code StudentRepositoryTest} с двумя владельцами
 * на каждом методе.
 *
 * Правил и проверок прав репозиторий не содержит: их ставит
 * {@link StudentService}.
 */
@Repository
public class StudentRepository {

    private final JdbcClient database;

    public StudentRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Все Ученики владельца, по алфавиту.
     *
     * Однофамильцы допустимы, поэтому вторым ключом порядка стоит
     * идентификатор: два одинаковых имени не должны меняться местами
     * от чтения к чтению.
     */
    public List<Student> findAll(UserId owner) {
        return database.sql("select id, user_id, name from student where user_id = ? order by lower(name), id")
                .param(owner.value())
                .query(StudentRepository::student)
                .list();
    }

    /** Ученик владельца; чужой или несуществующий — пусто. */
    public Optional<Student> findById(UserId owner, StudentId id) {
        return database.sql("select id, user_id, name from student where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .query(StudentRepository::student)
                .optional();
    }

    /** Заводит Ученика у владельца и возвращает его идентификатор. */
    public StudentId create(UserId owner, String name) {
        Long id = database.sql("insert into student (user_id, name) values (?, ?) returning id")
                .params(owner.value(), name)
                .query(Long.class)
                .single();
        return new StudentId(id);
    }

    /** Переименовывает Ученика владельца; чужого не трогает. */
    public void rename(UserId owner, StudentId id, String name) {
        database.sql("update student set name = ? where user_id = ? and id = ?")
                .params(name, owner.value(), id.value())
                .update();
    }

    /** Удаляет Ученика владельца; чужого не трогает. */
    public void delete(UserId owner, StudentId id) {
        database.sql("delete from student where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    private static Student student(ResultSet rs, int rowNum) throws SQLException {
        return new Student(new StudentId(rs.getLong("id")), new UserId(rs.getLong("user_id")), rs.getString("name"));
    }
}
