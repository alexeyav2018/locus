package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.user.UserId;

/**
 * Граница общего и личного: библиотека теории не знает, чья она.
 *
 * Теоретические материалы — общая библиотека, и фильтр по владельцу к ней
 * не применяется никогда: применённый, он сделал бы её невидимой
 * (antipatterns.md, первый пункт). Проверяется не отсутствие фильтра —
 * проверять было бы нечего, — а то, что владельца сюда невозможно передать:
 * у методов нет параметра {@link UserId}, поэтому «на всякий случай
 * отфильтровать» не получится даже по недосмотру (ADR-0027).
 *
 * Тест смотрит и на код, и на схему: колонка «чей» в таблице была бы началом
 * того же фильтра, только начатого с другого конца. Устроен он по образцу
 * {@link ru.locus.problem.OwnerIsUnknownToProblemsTest} — с добавленной
 * проверкой таблицы, потому что у теории таблица одна и вся её целиком видно.
 *
 * Список классов пополняется вместе с областью: каждый новый класс
 * {@code ru.locus.theory} вносится сюда, иначе владелец заведётся именно
 * в том, который забыли.
 */
class OwnerIsUnknownToTheoryTest extends IntegrationTest {

    private static final List<Class<?>> THEORY = List.of(
            TheoryService.class,
            TheoryMaterialRepository.class,
            TheoryMaterial.class,
            TheoryMaterialId.class,
            UploadedContent.class,
            NodeTheory.class);

    /** Слова, которыми назвали бы колонку владельца. */
    private static final List<String> OWNER_WORDS = List.of("owner", "user", "teacher", "account");

    @Autowired
    private JdbcClient database;

    @Test
    void noMethodTakesTheOwner() {
        for (Class<?> type : THEORY) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("метод %s.%s не должен принимать владельца", type.getSimpleName(), method.getName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void noConstructorTakesTheOwner() {
        for (Class<?> type : THEORY) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("конструктор %s не должен принимать владельца", type.getSimpleName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void nothingIsStoredAboutTheOwner() {
        Stream<Field> fields = THEORY.stream().flatMap(type -> Arrays.stream(type.getDeclaredFields()));

        assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                .as("поле %s.%s не должно хранить владельца", field.getDeclaringClass().getSimpleName(),
                        field.getName())
                .isNotEqualTo(UserId.class));
    }

    @Test
    void noMethodReturnsTheOwner() {
        for (Class<?> type : THEORY) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("метод %s.%s не должен отдавать владельца", type.getSimpleName(), method.getName())
                        .isNotEqualTo(UserId.class);
            }
        }
    }

    /**
     * В таблице материала колонки владельца нет — и не может завестись
     * незаметно: миграция, добавившая её, уронит этот тест.
     */
    @Test
    void tableHasNoColumnForTheOwner() {
        List<String> columns = database.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'theory_material'
                        """)
                .query(String.class)
                .list();

        assertThat(columns)
                .as("теория — общая библиотека: колонки «чей» в ней нет")
                .containsExactlyInAnyOrder("id", "title", "node_id", "file_key", "link")
                .allSatisfy(column -> assertThat(OWNER_WORDS)
                        .allSatisfy(word -> assertThat(column).doesNotContain(word)));
    }
}
