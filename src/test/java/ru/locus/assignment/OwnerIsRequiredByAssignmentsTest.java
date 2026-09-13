package ru.locus.assignment;

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
 * Граница общего и личного для Заданий: репозитории области знают, чьи
 * они, — и не могут не знать.
 *
 * Как в {@link ru.locus.student.OwnerIsRequiredByStudentsTest}: у каждого
 * публичного метода репозиториев среди параметров есть {@link UserId},
 * а в каждой таблице — колонка {@code user_id}. Забытый фильтр на личных
 * данных не падает, а тихо показывает учителю чужие Задания; параметр
 * в сигнатуре ловит это компилятором (ADR-0027).
 *
 * Новое здесь — ПОИМЁННЫЙ список исключений с причинами. ADR-0036 открыл
 * класс методов без владельца: вопрос библиотеки к личному контуру о своей
 * сущности, с одним числом или фактом наружу. Список именно поимённый,
 * а не «методы, возвращающие число, разрешены»: признак по типу пропустил
 * бы любой счёт без владельца, в том числе забытый, — и каждый такой метод
 * заводился бы молча. Имя в списке означает, что кто-то прочитал метод,
 * назвал причину и оставил её в диффе теста. Пополнение списка — строка
 * здесь, а не новая запись журнала; метод, возвращающий записи личного
 * контура без владельца, в класс не входит и в списке оказаться не может.
 *
 * Проверяется форма, а не поведение: параметр можно принять и не вписать
 * в запрос. Поведение — что чужой владелец получает пусто на каждом
 * методе, а {@code countByProblem} считает всех, — проверяют
 * {@code AssignmentRepositoryTest} и {@code AssignmentBatchRepositoryTest}.
 */
class OwnerIsRequiredByAssignmentsTest extends IntegrationTest {

    private static final List<Class<?>> REPOSITORIES =
            List.of(AssignmentRepository.class, AssignmentBatchRepository.class);

    /**
     * Методы без владельца — каждый с причиной. Ключ — «Класс.метод»,
     * чтобы переименование или переезд метода сделали исключение
     * недействительным, а не перенесли его молча.
     */
    private static final Map<String, String> WITHOUT_OWNER = Map.of(
            "AssignmentRepository.countByProblem",
            "вопрос библиотеки о своей Задаче (ProblemUsage): спрашивает Администратор, у которого личного "
                    + "контура нет, наружу уходит только число — класс исключений ADR-0036");

    private static final List<String> TABLES = List.of("assignment_batch", "assignment", "assignment_problem");

    @Autowired
    private JdbcClient database;

    @Test
    void everyPublicMethodTakesTheOwnerUnlessNamedHere() {
        for (Class<?> type : REPOSITORIES) {
            List<Method> exposed = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .toList();
            assertThat(exposed).as("у %s должны быть публичные методы", type.getSimpleName()).isNotEmpty();
            for (Method method : exposed) {
                String name = type.getSimpleName() + "." + method.getName();
                if (WITHOUT_OWNER.containsKey(name)) {
                    continue;
                }
                assertThat(method.getParameterTypes())
                        .as("метод %s обязан принимать владельца (ADR-0027) либо быть назван в списке исключений "
                                + "с причиной (ADR-0036)", name)
                        .contains(UserId.class);
            }
        }
    }

    /**
     * Список исключений не должен пережить метод: имя, которого больше
     * нет, — ложная запись, и её надо убрать вместе с причиной.
     */
    @Test
    void everyNamedExceptionExistsAndIndeedTakesNoOwner() {
        for (String name : WITHOUT_OWNER.keySet()) {
            String[] parts = name.split("\\.");
            Class<?> type = REPOSITORIES.stream()
                    .filter(candidate -> candidate.getSimpleName().equals(parts[0]))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("в списке исключений назван неизвестный класс: " + name));
            List<Method> named = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getName().equals(parts[1]))
                    .toList();
            assertThat(named).as("в списке исключений назван несуществующий метод: %s", name).isNotEmpty();
            for (Method method : named) {
                assertThat(method.getParameterTypes())
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
