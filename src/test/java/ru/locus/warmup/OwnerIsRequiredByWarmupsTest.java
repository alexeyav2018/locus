package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.user.UserId;

/**
 * Граница общего и личного для Разминок: репозиторий области знает, чьи
 * они, — и не может не знать.
 *
 * Как в {@link ru.locus.assignment.OwnerIsRequiredByAssignmentsTest}:
 * у каждого публичного метода {@link WarmupRepository} среди параметров
 * есть {@link UserId}, кроме названных поимённо с причиной (ADR-0036),
 * а в каждой таблице области — колонка {@code user_id} (ADR-0027).
 *
 * Проверяется форма, а не поведение: что чужой владелец получает пусто
 * на каждом методе, а {@code countByProblem} считает всех, — проверяет
 * {@link WarmupRepositoryTest}.
 */
class OwnerIsRequiredByWarmupsTest extends IntegrationTest {

    /**
     * Методы без владельца — каждый с причиной. Ключ — «Класс.метод»,
     * чтобы переименование или переезд метода сделали исключение
     * недействительным, а не перенесли его молча.
     */
    private static final Map<String, String> WITHOUT_OWNER = Map.of(
            "WarmupRepository.countByProblem",
            "вопрос библиотеки о своей Задаче (ProblemUsage): спрашивает Администратор, у которого личного "
                    + "контура нет, наружу уходит только число — класс исключений ADR-0036");

    private static final List<String> TABLES =
            List.of("warmup", "warmup_student", "warmup_problem", "warmup_outcome");

    @Autowired
    private JdbcClient database;

    @Test
    void everyPublicMethodTakesTheOwnerUnlessNamedHere() {
        List<Method> exposed = Arrays.stream(WarmupRepository.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();
        assertThat(exposed).as("у WarmupRepository должны быть публичные методы").isNotEmpty();
        for (Method method : exposed) {
            String name = "WarmupRepository." + method.getName();
            if (WITHOUT_OWNER.containsKey(name)) {
                continue;
            }
            assertThat(method.getParameterTypes())
                    .as("метод %s обязан принимать владельца (ADR-0027) либо быть назван в списке исключений "
                            + "с причиной (ADR-0036)", name)
                    .contains(UserId.class);
        }
    }

    /**
     * Список исключений не должен пережить метод: имя, которого больше
     * нет, — ложная запись, и её надо убрать вместе с причиной.
     */
    @Test
    void everyNamedExceptionExistsAndIndeedTakesNoOwner() {
        for (String name : WITHOUT_OWNER.keySet()) {
            String method = name.substring("WarmupRepository.".length());
            List<Method> named = Arrays.stream(WarmupRepository.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .toList();
            assertThat(named).as("в списке исключений назван несуществующий метод: %s", name).isNotEmpty();
            for (Method candidate : named) {
                assertThat(candidate.getParameterTypes())
                        .as("%s назван исключением, но владельца принимает — запись в списке лишняя", name)
                        .doesNotContain(UserId.class);
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
