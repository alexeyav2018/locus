package ru.locus.student;

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
 * Граница общего и личного, вторая половина: Ученики и Группы знают,
 * чьи они, — и не могут не знать.
 *
 * Это зеркало {@link ru.locus.theory.OwnerIsUnknownToTheoryTest}. Там
 * проверяется, что владельца в библиотеку невозможно передать; здесь —
 * что в личный контур его невозможно НЕ передать: у каждого публичного
 * метода репозиториев среди параметров есть {@link UserId}, а в каждой
 * таблице — колонка {@code user_id}. Забытый фильтр на личных данных
 * не падает, а тихо показывает учителю чужих учеников; параметр
 * в сигнатуре ловит это компилятором (ADR-0027).
 *
 * Проверяется форма, а не поведение: параметр можно принять и не вписать
 * в запрос. Поведение — что чужой владелец получает пусто на каждом методе —
 * проверяют {@code StudentRepositoryTest} и {@code GroupRepositoryTest}
 * двумя владельцами.
 *
 * Список классов пополняется вместе с областью: каждый новый репозиторий
 * {@code ru.locus.student} вносится сюда, иначе метод без владельца заведётся
 * именно в том, который забыли. {@code GroupRepository} встаёт сюда
 * с задачей 5.2.
 */
class OwnerIsRequiredByStudentsTest extends IntegrationTest {

    private static final List<Class<?>> REPOSITORIES = List.of(StudentRepository.class);

    private static final List<String> TABLES = List.of("student", "group_");

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

    /**
     * В каждой таблице области есть колонка владельца — и не может исчезнуть
     * незаметно: миграция, убравшая её, уронит этот тест.
     */
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
