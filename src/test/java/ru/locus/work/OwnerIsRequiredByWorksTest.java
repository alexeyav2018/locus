package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.user.UserId;

/**
 * Граница общего и личного для Работ: репозиторий знает, чьи они, —
 * и не может не знать.
 *
 * По образцу {@link ru.locus.student.OwnerIsRequiredByStudentsTest}:
 * у каждого публичного метода {@link StudentWorkRepository} среди
 * параметров есть {@link UserId}, а в каждой таблице области — колонка
 * {@code user_id} (ADR-0027). Списка исключений, как у Заданий
 * (ADR-0036), здесь нет и быть не должно: библиотека о Работах ничего
 * не спрашивает, а сканы работ — персональные данные детей, и метод
 * без владельца отдал бы их всем.
 *
 * Проверяется форма, а не поведение; поведение — что чужой владелец
 * получает пусто на каждом методе — проверяет
 * {@code StudentWorkRepositoryTest} двумя владельцами.
 */
class OwnerIsRequiredByWorksTest extends IntegrationTest {

    private static final List<Class<?>> REPOSITORIES = List.of(StudentWorkRepository.class);

    private static final List<String> TABLES = List.of("student_work", "student_work_file");

    @Autowired
    private JdbcClient database;

    @Test
    void everyPublicMethodTakesTheOwner() {
        for (Class<?> type : REPOSITORIES) {
            List<Method> exposed = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .toList();
            assertThat(exposed).as("у %s должны быть публичные методы", type.getSimpleName()).isNotEmpty();
            for (Method method : exposed) {
                assertThat(method.getParameterTypes())
                        .as("метод %s.%s обязан принимать владельца (ADR-0027); исключений у Работ нет",
                                type.getSimpleName(), method.getName())
                        .contains(UserId.class);
            }
        }
    }

    @Test
    void everyTableHasTheOwnerColumn() {
        for (String table : TABLES) {
            List<String> columns = database.sql("""
                            select column_name
                            from information_schema.columns
                            where table_name = ?
                            """)
                    .param(table)
                    .query(String.class)
                    .list();

            assertThat(columns)
                    .as("таблица %s — личный контур: без колонки «чей» её строки увидят все", table)
                    .isNotEmpty()
                    .contains("user_id");
        }
    }
}
