package ru.locus.mastery;

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
 * Граница общего и личного для отметок Владения: репозиторий области
 * знает, чьи они, — и не может не знать.
 *
 * Как в {@link ru.locus.assignment.OwnerIsRequiredByAssignmentsTest}:
 * у каждого публичного метода {@link MasteryRepository} среди параметров
 * есть {@link UserId}, а в таблице {@code mastery} — колонка {@code user_id}.
 * Забытый фильтр на личных данных не падает, а тихо показывает учителю
 * чужие суждения; параметр в сигнатуре ловит это компилятором (ADR-0027).
 *
 * Исключения — ПОИМЁННО с причинами, как и там. Здесь их четыре, и это
 * самый широкий список в проекте: у отметок не только вопросы библиотеки
 * (счёт по Теме и по Методу), но и ДЕЙСТВИЯ дерева над отметками всех
 * Учителей сразу — переезд при углублении и удаление при снятии
 * с распределением (ADR-0007). Владельца у таких действий нет по существу:
 * их совершает Администратор над общим узлом. Список именно поимённый,
 * а не «методы без возвращаемых записей разрешены»: имя в списке означает,
 * что кто-то прочитал метод, назвал причину и оставил её в диффе теста.
 *
 * Проверяется форма, а не поведение. Поведение — что чужой владелец
 * получает пусто, а методы без владельца считают и двигают отметки всех, —
 * проверяет {@code MasteryRepositoryTest}.
 */
class OwnerIsRequiredByMasteryTest extends IntegrationTest {

    private static final List<Class<?>> REPOSITORIES = List.of(MasteryRepository.class);

    /**
     * Методы без владельца — каждый с причиной. Ключ — «Класс.метод»,
     * чтобы переименование или переезд метода сделали исключение
     * недействительным, а не перенесли его молча.
     */
    private static final Map<String, String> WITHOUT_OWNER = Map.of(
            "MasteryRepository.countByTopic",
            "вопрос дерева о своей Теме (NodeContent.on, requiringTopic, countVanishing): спрашивает "
                    + "Администратор, у которого личного контура нет, наружу уходит только число — ADR-0036",
            "MasteryRepository.countByMethod",
            "вопрос словаря о своём Методе (DictionaryUsage.ofMethod): спрашивает Администратор, "
                    + "наружу уходит только число — ADR-0036",
            "MasteryRepository.rehomeTopic",
            "действие дерева над своей Темой (NodeContent.moveTopicContent): при углублении отметки ВСЕХ "
                    + "Учителей переезжают на приёмник разом, владельца у операции нет по существу — ADR-0007, ADR-0036",
            "MasteryRepository.deleteByTopic",
            "действие дерева над своей Темой (NodeContent.distributeTopicContent): при снятии с распределением "
                    + "отметки ВСЕХ Учителей исчезают разом, владельца у операции нет по существу — ADR-0007, ADR-0036");

    private static final List<String> TABLES = List.of("mastery");

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
     * В таблице области есть колонка владельца — и не может исчезнуть
     * незаметно: миграция, убравшая её, уронит этот тест. Здесь она ещё
     * и часть первичного ключа, но тест держит меньшее: наличие.
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
