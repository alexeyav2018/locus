package ru.locus.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Хранение учётных записей.
 *
 * Учётные записи — общее, под Администратором, поэтому параметра владельца
 * здесь нет и быть не должно (ADR-0027): фильтр по владельцу применяется
 * только к личному контуру.
 *
 * Решений о том, что кому видно, репозиторий не содержит: права проверяет
 * сервисный слой.
 */
@Repository
public class UserRepository {

    private final JdbcClient database;

    public UserRepository(JdbcClient database) {
        this.database = database;
    }

    public Optional<User> findByLogin(String login) {
        return database.sql("""
                        select id, login, password_hash, password_change_required
                        from user_
                        where login = ?
                        """)
                .param(login)
                .query(this::withoutRoles)
                .optional()
                .map(this::withRoles);
    }

    public Optional<User> findById(UserId id) {
        return database.sql("""
                        select id, login, password_hash, password_change_required
                        from user_
                        where id = ?
                        """)
                .param(id.value())
                .query(this::withoutRoles)
                .optional()
                .map(this::withRoles);
    }

    /** Все учётные записи в порядке имени входа — вместе с их ролями. */
    public List<User> findAll() {
        List<User> withoutRoles = database.sql("""
                        select id, login, password_hash, password_change_required
                        from user_
                        order by login
                        """)
                .query(this::withoutRoles)
                .list();

        Map<Long, Set<Role>> roles = allRoles();
        List<User> result = new ArrayList<>(withoutRoles.size());
        for (User user : withoutRoles) {
            result.add(withRoles(user, roles.getOrDefault(user.id().value(), EnumSet.noneOf(Role.class))));
        }
        return result;
    }

    /** Заводит запись без ролей и возвращает её идентификатор. */
    public UserId create(String login, String passwordHash, boolean passwordChangeRequired) {
        Long id = database.sql("""
                        insert into user_ (login, password_hash, password_change_required)
                        values (?, ?, ?)
                        returning id
                        """)
                .params(login, passwordHash, passwordChangeRequired)
                .query(Long.class)
                .single();
        return new UserId(id);
    }

    public void updatePassword(UserId id, String passwordHash, boolean passwordChangeRequired) {
        database.sql("""
                        update user_
                        set password_hash = ?, password_change_required = ?
                        where id = ?
                        """)
                .params(passwordHash, passwordChangeRequired, id.value())
                .update();
    }

    /** Назначение роли повторно — не ошибка: набор ролей есть множество. */
    public void assignRole(UserId id, Role role) {
        database.sql("""
                        insert into user_role (user_id, role)
                        values (?, ?)
                        on conflict do nothing
                        """)
                .params(id.value(), role.name())
                .update();
    }

    public void revokeRole(UserId id, Role role) {
        database.sql("delete from user_role where user_id = ? and role = ?")
                .params(id.value(), role.name())
                .update();
    }

    private User withoutRoles(ResultSet rs, int rowNum) throws SQLException {
        return new User(
                new UserId(rs.getLong("id")),
                rs.getString("login"),
                rs.getString("password_hash"),
                rs.getBoolean("password_change_required"),
                EnumSet.noneOf(Role.class));
    }

    private User withRoles(User user) {
        return withRoles(user, rolesOf(user.id()));
    }

    private User withRoles(User user, Set<Role> roles) {
        return new User(user.id(), user.login(), user.passwordHash(), user.passwordChangeRequired(), roles);
    }

    private Set<Role> rolesOf(UserId id) {
        List<String> names = database.sql("select role from user_role where user_id = ?")
                .param(id.value())
                .query(String.class)
                .list();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        names.forEach(name -> roles.add(Role.valueOf(name)));
        return roles;
    }

    private Map<Long, Set<Role>> allRoles() {
        Map<Long, Set<Role>> byUser = new LinkedHashMap<>();
        RowCallbackHandler collect = rs -> byUser
                .computeIfAbsent(rs.getLong("user_id"), key -> EnumSet.noneOf(Role.class))
                .add(Role.valueOf(rs.getString("role")));
        database.sql("select user_id, role from user_role").query(collect);
        return byUser;
    }
}
