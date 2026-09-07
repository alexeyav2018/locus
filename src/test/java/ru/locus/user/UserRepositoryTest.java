package ru.locus.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;

/**
 * Хранение учётных записей: созданная запись читается обратно вместе с ролями.
 *
 * Проверка идёт на настоящей базе в контейнере — подмены репозитория нет:
 * половина проверяемого здесь живёт в SQL и в схеме, а не в Java.
 */
class UserRepositoryTest extends IntegrationTest {

    @Autowired
    private UserRepository users;

    @Test
    void createdAccountIsReadBackWithItsRoles() {
        String login = uniqueLogin();
        UserId id = users.create(login, "{noop}пароль", true);
        users.assignRole(id, Role.TEACHER);
        users.assignRole(id, Role.ADMINISTRATOR);

        User found = users.findByLogin(login).orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.login()).isEqualTo(login);
        assertThat(found.passwordHash()).isEqualTo("{noop}пароль");
        assertThat(found.passwordChangeRequired()).isTrue();
        assertThat(found.roles())
                .as("обе назначенные роли прочитались")
                .isEqualTo(EnumSet.of(Role.ADMINISTRATOR, Role.TEACHER));
        assertThat(users.findById(id)).contains(found);
    }

    @Test
    void accountWithoutRolesIsReadBackWithEmptyRoleSet() {
        UserId id = users.create(uniqueLogin(), "{noop}пароль", false);

        User found = users.findById(id).orElseThrow();

        assertThat(found.roles()).isEqualTo(Set.of());
        assertThat(found.passwordChangeRequired()).isFalse();
    }

    @Test
    void revokedRoleDisappearsAndAssigningTwiceIsNotAnError() {
        UserId id = users.create(uniqueLogin(), "{noop}пароль", false);

        users.assignRole(id, Role.TEACHER);
        users.assignRole(id, Role.TEACHER);
        assertThat(users.findById(id).orElseThrow().roles()).isEqualTo(EnumSet.of(Role.TEACHER));

        users.revokeRole(id, Role.TEACHER);
        assertThat(users.findById(id).orElseThrow().roles()).isEqualTo(Set.of());
    }

    @Test
    void changedPasswordAndFlagAreReadBack() {
        UserId id = users.create(uniqueLogin(), "{noop}старый", true);

        users.updatePassword(id, "{noop}новый", false);

        User found = users.findById(id).orElseThrow();
        assertThat(found.passwordHash()).isEqualTo("{noop}новый");
        assertThat(found.passwordChangeRequired()).isFalse();
    }

    @Test
    void listContainsTheFirstAdministratorFromMigration() {
        assertThat(users.findAll())
                .as("список отдаёт записи вместе с ролями")
                .anySatisfy(user -> {
                    assertThat(user.login()).isEqualTo("admin");
                    assertThat(user.roles()).contains(Role.ADMINISTRATOR);
                });
    }

    @Test
    void unknownLoginIsNotFound() {
        assertThat(users.findByLogin(uniqueLogin())).isEmpty();
    }

    /** Тесты идут на одной базе, поэтому имя входа у каждого своё. */
    private static String uniqueLogin() {
        return "user-" + UUID.randomUUID();
    }
}
