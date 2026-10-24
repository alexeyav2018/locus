package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.file.FileType;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Черновик Задачи принадлежит загрузившему (ADR-0059): репозиторий знает,
 * чей черновик, — и не может не знать.
 *
 * По образцу {@link OwnerIsRequiredByDraftsTest}: у каждого публичного
 * метода {@link ProblemDraftRepository} среди параметров есть
 * {@link UserId}, кроме названных поимённо с причиной. Исключения —
 * уборки того же класса, что у Черновика сборки (ADR-0044): обе только
 * удаляют и наружу отдают одно число. Метод, отдающий черновики без
 * владельца, в этот список попасть не может.
 *
 * Второй рубеж — база: строка сборки ссылается на Черновик сборки
 * составным ключом с владельцем, и чужой Черновик сборки она не примет,
 * даже если сервис проверку пропустит.
 */
class OwnerIsRequiredByProblemDraftsTest extends IntegrationTest {

    private static final Class<?> REPOSITORY = ProblemDraftRepository.class;

    private static final Map<String, String> WITHOUT_OWNER = Map.of(
            "ProblemDraftRepository.deleteChangedBefore",
            "уборка брошенных по сроку от последней правки — черновики всех Пользователей, наружу только "
                    + "число удалённых (ADR-0059)",
            "ProblemDraftRepository.deleteAll",
            "уборка всех черновиков при старте приложения вместе с Черновиками сборки, наружу только "
                    + "число удалённых (ADR-0059)");

    @Autowired
    private JdbcClient database;

    @Autowired
    private ProblemDraftRepository drafts;

    @Autowired
    private AssemblyDraftRepository assemblyDrafts;

    @Autowired
    private TestAccounts accounts;

    @AfterEach
    void removeDrafts() {
        drafts.deleteAll();
    }

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
                            + "с причиной (ADR-0059)", name)
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
                assertThat(method.getReturnType())
                        .as("%s — уборка: наружу только число удалённых, а не черновики", name)
                        .isEqualTo(int.class);
            }
        }
    }

    @Test
    void draftTablesHaveTheOwnerColumn() {
        for (String table : List.of("problem_draft", "problem_draft_line")) {
            List<String> columns = database.sql("""
                            select column_name
                            from information_schema.columns
                            where table_name = ?
                            """)
                    .param(table)
                    .query(String.class)
                    .list();

            assertThat(columns).as("%s без колонки «чей» увидят все", table).contains("user_id");
        }
    }

    @Test
    void databaseRefusesALineOnAnotherOwnersAssemblyDraft() {
        UserId alice = accounts.settled(Role.ADMINISTRATOR).id();
        UserId bob = accounts.settled(Role.ADMINISTRATOR).id();
        AssemblyDraftId alicesPhoto = assemblyDrafts.create(alice, "лист.jpg", AssemblyDraft.Kind.IMAGE,
                FileType.JPEG, 1, UUID.randomUUID().toString(), Instant.now());
        ProblemDraftContent onAlicesPhoto = new ProblemDraftContent(null, "", "", new PdfAssemblyOrder(List.of(
                new PdfAssemblyOrder.Line(alicesPhoto, 1, 1))), null, null, null, null, null, null);

        assertThatThrownBy(() -> drafts.create(bob, onAlicesPhoto, ProblemDraft.Editor.ASSISTANT, Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
