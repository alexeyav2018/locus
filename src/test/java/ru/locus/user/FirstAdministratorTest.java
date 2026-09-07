package ru.locus.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.locus.PostgresImage;

/**
 * Требование «Система с первого запуска имеет одного Администратора».
 *
 * Проверяется результат миграции, а не прикладной код: учётная запись
 * заводится changeset'ом, до и вне приложения (ADR-0026).
 *
 * Контейнер у этого теста свой, а не общий на прогон: требование говорит
 * о базе, где нет ничего, а общую базу остальные тесты населяют своими
 * учётными записями — и «ровно одна» перестало бы что-либо значить.
 */
@SpringBootTest
class FirstAdministratorTest {

    @ServiceConnection
    static final PostgreSQLContainer EMPTY_DATABASE = new PostgreSQLContainer(PostgresImage.fromComposeFile());

    static {
        EMPTY_DATABASE.start();
    }

    @Autowired
    private JdbcClient database;

    @Autowired
    private SpringLiquibase liquibase;

    /** Сценарий «Первый запуск на пустой базе». */
    @Test
    void emptyDatabaseGetsExactlyOneAdministratorWithPasswordToChange() {
        List<String> accounts = database.sql("select login from user_")
                .query(String.class)
                .list();

        assertThat(accounts)
                .as("после миграций на пустой базе учётная запись ровно одна")
                .containsExactly("admin");
        assertThat(rolesOfAdmin())
                .as("и это Администратор")
                .containsExactly("ADMINISTRATOR");

        assertThat(passwordChangeRequired())
                .as("его пароль помечен подлежащим смене")
                .isTrue();
    }

    /** Сценарий «Повторный запуск»: сменённый пароль не затирается миграцией. */
    @Test
    void repeatedStartupKeepsChangedPassword() throws Exception {
        String initialHash = passwordHash();
        String changedHash = "{noop}смененный-руками";
        try {
            database.sql("update user_ set password_hash = ?, password_change_required = false where login = 'admin'")
                    .param(changedHash)
                    .update();

            liquibase.afterPropertiesSet();

            assertThat(accountsNamedAdmin())
                    .as("учётная запись не создана заново")
                    .isEqualTo(1);
            assertThat(passwordHash())
                    .as("сменённый пароль остаётся действующим")
                    .isEqualTo(changedHash);
            assertThat(passwordChangeRequired())
                    .as("снятая пометка не поднимается заново")
                    .isFalse();
        } finally {
            database.sql("update user_ set password_hash = ?, password_change_required = true where login = 'admin'")
                    .param(initialHash)
                    .update();
        }
    }

    private List<String> rolesOfAdmin() {
        return database.sql("""
                        select r.role
                        from user_role r
                        join user_ u on u.id = r.user_id
                        where u.login = 'admin'
                        """)
                .query(String.class)
                .list();
    }

    private long accountsNamedAdmin() {
        return database.sql("select count(*) from user_ where login = 'admin'")
                .query(Long.class)
                .single();
    }

    private String passwordHash() {
        return database.sql("select password_hash from user_ where login = 'admin'")
                .query(String.class)
                .single();
    }

    private boolean passwordChangeRequired() {
        return database.sql("select password_change_required from user_ where login = 'admin'")
                .query(Boolean.class)
                .single();
    }
}
