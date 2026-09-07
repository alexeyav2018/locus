package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Требование «Схема базы приводится в актуальное состояние при старте».
 */
class MigrationsOnStartupTest extends IntegrationTest {

    @Autowired
    private JdbcClient database;

    @Autowired
    private SpringLiquibase liquibase;

    @Test
    void migrationsApplyOnEmptyDatabase() {
        assertThat(tableExists("databasechangeloglock"))
                .as("Liquibase отработал на пустой базе и создал таблицу блокировки")
                .isTrue();
        assertThat(tableExists("databasechangelog"))
                .as("Liquibase завёл журнал применённых миграций")
                .isTrue();
    }

    @Test
    void repeatedStartupAppliesNothingAgain() {
        long before = appliedCount();

        assertThatCode(() -> liquibase.afterPropertiesSet())
                .as("повторный накат на актуальной базе проходит без ошибки")
                .doesNotThrowAnyException();

        assertThat(appliedCount())
                .as("ни одна миграция не применена повторно")
                .isEqualTo(before);
    }

    private boolean tableExists(String name) {
        return database.sql("""
                        select exists (
                            select 1 from pg_tables
                            where schemaname = 'public' and lower(tablename) = lower(?)
                        )
                        """)
                .param(name)
                .query(Boolean.class)
                .single();
    }

    private long appliedCount() {
        return database.sql("select count(*) from databasechangelog")
                .query(Long.class)
                .single();
    }
}
