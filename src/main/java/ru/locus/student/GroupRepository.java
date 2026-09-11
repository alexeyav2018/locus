package ru.locus.student;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.user.UserId;

/**
 * Хранение Групп и их состава — второй репозиторий ЛИЧНОГО контура.
 *
 * Как и у {@link StudentRepository}, у каждого метода есть параметр
 * владельца {@link UserId}, и в каждом SQL стоит {@code user_id = ?}
 * (ADR-0027). Чужая Группа для вызывающего неотличима от несуществующей:
 * чтение отдаёт пусто, правка не меняет ни одной строки.
 *
 * Состав хранится в {@code group_student}, и владелец записан в каждой
 * его строке. Это не дублирование ради удобства: составные внешние ключи
 * {@code (group_id, user_id)} и {@code (student_id, user_id)} требуют,
 * чтобы Группа и Ученик принадлежали одному Учителю, — строке с чужим
 * Учеником не на что сослаться, и база её не примет. Сервис проверяет
 * то же раньше и с внятным сообщением, но инвариант держит схема,
 * а не дисциплина (design.md, «Принадлежность состава одному владельцу»).
 *
 * Уникальность имени у владельца без учёта регистра держит индекс
 * {@code uq_group_user_name}; {@link #findByName} сравнивает тем же
 * {@code lower(name)}, чтобы проверка сервиса и индекс не разошлись.
 *
 * Правил и проверок прав репозиторий не содержит: их ставит
 * {@link GroupService}.
 */
@Repository
public class GroupRepository {

    private final JdbcClient database;

    public GroupRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Все Группы владельца, по алфавиту.
     *
     * Имена уникальны без учёта регистра, поэтому порядок задаётся
     * {@code lower(name)} — как у словарей.
     */
    public List<Group> findAll(UserId owner) {
        return database.sql("select id, user_id, name from group_ where user_id = ? order by lower(name)")
                .param(owner.value())
                .query(GroupRepository::group)
                .list();
    }

    /** Группа владельца; чужая или несуществующая — пусто. */
    public Optional<Group> findById(UserId owner, GroupId id) {
        return database.sql("select id, user_id, name from group_ where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .query(GroupRepository::group)
                .optional();
    }

    /**
     * Группа владельца по имени без учёта регистра — тем же сравнением,
     * что у индекса {@code uq_group_user_name}.
     */
    public Optional<Group> findByName(UserId owner, String name) {
        return database.sql("select id, user_id, name from group_ where user_id = ? and lower(name) = lower(?)")
                .params(owner.value(), name)
                .query(GroupRepository::group)
                .optional();
    }

    /** Заводит Группу у владельца и возвращает её идентификатор. */
    public GroupId create(UserId owner, String name) {
        Long id = database.sql("insert into group_ (user_id, name) values (?, ?) returning id")
                .params(owner.value(), name)
                .query(Long.class)
                .single();
        return new GroupId(id);
    }

    /** Переименовывает Группу владельца; чужую не трогает. */
    public void rename(UserId owner, GroupId id, String name) {
        database.sql("update group_ set name = ? where user_id = ? and id = ?")
                .params(name, owner.value(), id.value())
                .update();
    }

    /**
     * Удаляет Группу владельца; чужую не трогает.
     *
     * Состав уходит каскадом {@code fk_group_student_group}: членство —
     * список, а не суждение (ADR-0035). Ученики остаются.
     */
    public void delete(UserId owner, GroupId id) {
        database.sql("delete from group_ where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    /**
     * Состав Группы владельца — Ученики по алфавиту; чужая
     * или несуществующая Группа — пустой список.
     */
    public List<StudentId> members(UserId owner, GroupId group) {
        return database.sql("""
                        select gs.student_id
                        from group_student gs
                        join student s on s.id = gs.student_id
                        where gs.user_id = ? and gs.group_id = ?
                        order by lower(s.name), s.id
                        """)
                .params(owner.value(), group.value())
                .query(Long.class)
                .list()
                .stream()
                .map(StudentId::new)
                .toList();
    }

    /**
     * Задаёт состав Группы целиком: прежние строки снимаются, новые
     * вставляются, владелец записан в каждой.
     *
     * Ученик другого владельца или чужая Группа не вставляются: составным
     * ключам не на что сослаться, и база отвечает нарушением ограничения.
     * Сервис не доводит до этого — он читает каждого Ученика через
     * {@code StudentRepository.findById(owner, id)} и отказывает раньше;
     * здесь ограничение — последний рубеж, а не способ проверки.
     *
     * Повторы в списке схлопываются: состав — множество, и дважды
     * вписанный Ученик уронил бы первичный ключ, а не добавил бы строку.
     * Атомарность двух шагов — дело транзакции сервиса.
     */
    public void setMembers(UserId owner, GroupId group, List<StudentId> students) {
        database.sql("delete from group_student where user_id = ? and group_id = ?")
                .params(owner.value(), group.value())
                .update();
        for (StudentId student : students.stream().distinct().toList()) {
            database.sql("insert into group_student (group_id, student_id, user_id) values (?, ?, ?)")
                    .params(group.value(), student.value(), owner.value())
                    .update();
        }
    }

    /** Группы владельца, в которых состоит Ученик, по алфавиту; чужой Ученик — пусто. */
    public List<Group> groupsOf(UserId owner, StudentId student) {
        return database.sql("""
                        select g.id, g.user_id, g.name
                        from group_ g
                        join group_student gs on gs.group_id = g.id and gs.user_id = g.user_id
                        where g.user_id = ? and gs.student_id = ?
                        order by lower(g.name)
                        """)
                .params(owner.value(), student.value())
                .query(GroupRepository::group)
                .list();
    }

    private static Group group(ResultSet rs, int rowNum) throws SQLException {
        return new Group(new GroupId(rs.getLong("id")), new UserId(rs.getLong("user_id")), rs.getString("name"));
    }
}
