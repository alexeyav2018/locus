package ru.locus.lesson;

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
 * Граница общего и личного для Занятий: в личный контур владельца невозможно
 * не передать (ADR-0027). У каждого публичного метода {@link LessonRepository}
 * есть {@link UserId}, у таблицы {@code lesson} — колонка {@code user_id}.
 * Исключений класса ADR-0036 нет: библиотека о Занятиях не спрашивает,
 * и появиться такое исключение может только вместе с записью журнала.
 *
 * <p>Проверяется форма; поведение — что чужой владелец получает пусто —
 * проверяет {@code LessonRepositoryTest} двумя владельцами.
 */
class OwnerIsRequiredByLessonsTest extends IntegrationTest {

    private static final List<Class<?>> REPOSITORIES = List.of(LessonRepository.class);

    private static final List<String> TABLES = List.of("lesson");

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
                        .as("метод %s.%s обязан принимать владельца (ADR-0027)", type.getSimpleName(), method.getName())
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
