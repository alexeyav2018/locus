package ru.locus.assignment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.user.UserId;

/**
 * Хранение Раздач — общего происхождения Заданий, выданных Группе одним
 * действием (ADR-0016).
 *
 * Как у {@link ru.locus.student.StudentRepository}, у каждого метода есть
 * параметр владельца {@link UserId}, и в каждом SQL стоит {@code user_id = ?}
 * (ADR-0027): чужая Раздача для вызывающего неотличима от несуществующей —
 * чтение отдаёт пусто, удаление не меняет ни одной строки. Форму стережёт
 * {@code OwnerIsRequiredByAssignmentsTest}, поведение —
 * {@code AssignmentBatchRepositoryTest} двумя владельцами на каждом методе.
 *
 * Раздача хранит имя Группы текстом, а не ключом на {@code group_}: Группа
 * удаляется в любой момент (ADR-0035), а имя на момент выдачи —
 * исторический факт (см. {@link AssignmentBatch}). Потому здесь нет
 * ни соединения с Группами, ни метода «Раздачи Группы».
 *
 * Задания Раздачи этот репозиторий не трогает: ключ
 * {@code fk_assignment_batch} без каскада, и Раздача с Заданиями
 * не удалится — сервис снимает Задания через
 * {@link AssignmentRepository#deleteByBatch} и только затем Раздачу,
 * в одной транзакции (ADR-0037). Правил и проверок прав здесь нет:
 * их ставит {@code AssignmentService}.
 */
@Repository
public class AssignmentBatchRepository {

    private final JdbcClient database;

    public AssignmentBatchRepository(JdbcClient database) {
        this.database = database;
    }

    /** Заводит Раздачу у владельца и возвращает её идентификатор. */
    public AssignmentBatchId create(UserId owner, String groupName, LocalDate issuedOn) {
        Long id = database.sql("insert into assignment_batch (user_id, group_name, issued_on) values (?, ?, ?) returning id")
                .params(owner.value(), groupName, issuedOn)
                .query(Long.class)
                .single();
        return new AssignmentBatchId(id);
    }

    /** Раздача владельца; чужая или несуществующая — пусто. */
    public Optional<AssignmentBatch> findById(UserId owner, AssignmentBatchId id) {
        return database.sql("select id, user_id, group_name, issued_on from assignment_batch where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .query(AssignmentBatchRepository::batch)
                .optional();
    }

    /**
     * Все Раздачи владельца, новые первыми.
     *
     * Дат выдачи в один день может быть несколько, поэтому вторым ключом
     * порядка стоит идентификатор, убывающий вместе с датой: две Раздачи
     * одного дня не должны меняться местами от чтения к чтению.
     */
    public List<AssignmentBatch> findAll(UserId owner) {
        return database.sql("""
                        select id, user_id, group_name, issued_on
                        from assignment_batch
                        where user_id = ?
                        order by issued_on desc, id desc
                        """)
                .param(owner.value())
                .query(AssignmentBatchRepository::batch)
                .list();
    }

    /**
     * Удаляет Раздачу владельца; чужую не трогает.
     *
     * Раздачу с Заданиями база не отдаст: ключ {@code fk_assignment_batch}
     * без каскада. Это не способ проверки, а последний рубеж — сервис
     * снимает Задания раньше.
     */
    public void delete(UserId owner, AssignmentBatchId id) {
        database.sql("delete from assignment_batch where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    private static AssignmentBatch batch(ResultSet rs, int rowNum) throws SQLException {
        return new AssignmentBatch(
                new AssignmentBatchId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                rs.getString("group_name"),
                rs.getObject("issued_on", LocalDate.class));
    }
}
