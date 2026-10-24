package ru.locus.assistant;

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
 * Граница общего и личного для Разговоров и Реплик: в личный контур владельца
 * невозможно не передать (ADR-0027).
 *
 * По образцу {@link ru.locus.problem.OwnerIsRequiredByDraftsTest}: у каждого
 * публичного метода {@link AssistantRepository} есть {@link UserId}, кроме
 * названного поимённо с причиной, а у всех трёх таблиц — колонка
 * {@code user_id}. Исключение одно — уборка при старте класса ADR-0036:
 * только меняет состояние и наружу отдаёт одно число. Метод, отдающий
 * Разговоры или Реплики без владельца, в этот список попасть не может.
 *
 * <p>Проверяется форма; поведение — что чужой владелец получает пусто —
 * проверяет {@code AssistantIsFilteredByOwnerTest} двумя учителями.
 */
class OwnerIsRequiredByAssistantTest extends IntegrationTest {

    private static final Class<?> REPOSITORY = AssistantRepository.class;

    private static final Map<String, String> WITHOUT_OWNER = Map.of(
            "AssistantRepository.interruptRunning",
            "уборка при старте: процессов Помощника после перезапуска нет ни у кого, и каждая Реплика "
                    + "в «работаю» помечается прерванной; наружу только число помеченных (ADR-0036, ADR-0054)");

    private static final List<String> TABLES =
            List.of("assistant_conversation", "assistant_turn", "assistant_turn_step");

    @Autowired
    private JdbcClient database;

    @Test
    void everyPublicMethodTakesTheOwnerUnlessNamedHere() {
        List<Method> exposed = Arrays.stream(REPOSITORY.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();
        assertThat(exposed).isNotEmpty();
        for (Method method : exposed) {
            String name = REPOSITORY.getSimpleName() + "." + method.getName();
            if (WITHOUT_OWNER.containsKey(name)) {
                continue;
            }
            assertThat(method.getParameterTypes())
                    .as("метод %s обязан принимать владельца (ADR-0027) либо быть назван в списке исключений "
                            + "с причиной (ADR-0036)", name)
                    .contains(UserId.class);
        }
    }

    /** Исключение не должно пережить метод, принимать владельца и отдавать записи. */
    @Test
    void everyNamedExceptionExistsAndIndeedTakesNoOwner() {
        for (String name : WITHOUT_OWNER.keySet()) {
            String methodName = name.substring(name.indexOf('.') + 1);
            List<Method> named = Arrays.stream(REPOSITORY.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .toList();
            assertThat(named).as("в списке исключений назван несуществующий метод: %s", name).isNotEmpty();
            for (Method method : named) {
                assertThat(method.getParameterTypes())
                        .as("%s назван исключением, но владельца принимает — запись в списке лишняя", name)
                        .doesNotContain(UserId.class);
                assertThat(method.getReturnType())
                        .as("%s — уборка: наружу только число помеченных, а не Реплики", name)
                        .isEqualTo(int.class);
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
