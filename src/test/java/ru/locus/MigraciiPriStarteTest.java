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
class MigraciiPriStarteTest extends IntegracionnyjTest {

    @Autowired
    private JdbcClient bazaDannyh;

    @Autowired
    private SpringLiquibase liquibase;

    @Test
    void naPustojBazeMigraciiNakatyvayutsya() {
        assertThat(sushchestvuetTablica("databasechangeloglock"))
                .as("Liquibase отработал на пустой базе и создал таблицу блокировки")
                .isTrue();
        assertThat(sushchestvuetTablica("databasechangelog"))
                .as("Liquibase завёл журнал применённых миграций")
                .isTrue();
    }

    @Test
    void povtornyjZapuskNichegoNePrimenyaetZanovo() {
        long bylo = primeneno();

        assertThatCode(() -> liquibase.afterPropertiesSet())
                .as("повторный накат на актуальной базе проходит без ошибки")
                .doesNotThrowAnyException();

        assertThat(primeneno())
                .as("ни одна миграция не применена повторно")
                .isEqualTo(bylo);
    }

    private boolean sushchestvuetTablica(String imya) {
        return bazaDannyh.sql("""
                        select exists (
                            select 1 from pg_tables
                            where schemaname = 'public' and lower(tablename) = lower(?)
                        )
                        """)
                .param(imya)
                .query(Boolean.class)
                .single();
    }

    private long primeneno() {
        return bazaDannyh.sql("select count(*) from databasechangelog")
                .query(Long.class)
                .single();
    }
}
