package ru.locus.problem;

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
 * Черновик сборки принадлежит загрузившему (ADR-0041): репозиторий знает,
 * чей черновик, — и не может не знать.
 *
 * По образцу {@link ru.locus.assignment.OwnerIsRequiredByAssignmentsTest}:
 * у каждого публичного метода {@link AssemblyDraftRepository} среди
 * параметров есть {@link UserId}, кроме названных поимённо с причиной.
 * Исключения здесь не класса ADR-0036 — не вопросы библиотеки, а уборки
 * брошенного, которые ADR-0041 оговаривает отдельно: обе только удаляют
 * и наружу отдают одни имена файлов. Метод, отдающий черновики без
 * владельца, в этот список попасть не может.
 */
class OwnerIsRequiredByDraftsTest extends IntegrationTest {

    private static final Class<?> REPOSITORY = AssemblyDraftRepository.class;

    private static final Map<String, String> WITHOUT_OWNER = Map.of(
            "AssemblyDraftRepository.deleteCreatedBefore",
            "уборка брошенных по сроку при открытии инструмента — черновики всех Пользователей, наружу только "
                    + "имена файлов для удаления с диска (ADR-0041)",
            "AssemblyDraftRepository.deleteAll",
            "уборка всех черновиков при старте приложения, наружу только имена файлов (ADR-0041)");

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
                    .as("метод %s обязан принимать владельца (ADR-0027) либо быть назван в списке уборок "
                            + "с причиной (ADR-0041)", name)
                    .contains(UserId.class);
        }
    }

    /** Исключение не должно пережить метод и не должно принимать владельца. */
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
                assertThat(method.getGenericReturnType().getTypeName())
                        .as("%s — уборка: наружу только имена файлов, а не черновики", name)
                        .isEqualTo("java.util.List<java.lang.String>");
            }
        }
    }

    @Test
    void draftTableHasTheOwnerColumn() {
        List<String> columns = database.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'assembly_draft'
                        """)
                .query(String.class)
                .list();

        assertThat(columns).as("черновик без колонки «чей» увидят все").contains("user_id");
    }
}
