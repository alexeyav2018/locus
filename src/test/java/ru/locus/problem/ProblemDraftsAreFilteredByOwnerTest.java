package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Задача 3.5 problem-intake: Черновик Задачи — собственность загрузившего
 * (ADR-0059), и глазами второго Администратора его нет. Б не читает,
 * не пишет, не сохраняет и не удаляет черновик А, и отказ тот же, что
 * на несуществующий; строка по Черновику сборки А в черновике Б отклонена
 * сервисом (составной ключ базы — в {@code OwnerIsRequiredByProblemDraftsTest}).
 */
class ProblemDraftsAreFilteredByOwnerTest extends IntegrationTest {

    private static final ProblemDraftId MISSING = new ProblemDraftId(Long.MAX_VALUE);

    @Autowired
    private ProblemDraftService service;

    @Autowired
    private ProblemDraftRepository repository;

    @Autowired
    private AssemblyDraftService assemblyDrafts;

    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account alice;
    private AssemblyDraft alicesPhoto;
    private ProblemDraft alicesDraft;

    @BeforeEach
    void aliceDrafts() throws IOException {
        alice = accounts.settled(Role.ADMINISTRATOR);
        LoggedIn.as(alice);
        alicesPhoto = assemblyDrafts.upload("лист.png", new ByteArrayInputStream(AssemblyDraftServiceTest.png()));
        alicesDraft = service.create(new ProblemDraftContent(null, "Условие А", "Решение А", whole(alicesPhoto),
                null, null, null, null, null, null), ProblemDraft.Editor.ASSISTANT);
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        LoggedIn.nobody();
    }

    @Test
    void bobDoesNotSeeAlicesDraft() {
        assertThat(service.drafts()).isEmpty();
        assertSameRefusal(() -> service.draft(alicesDraft.id()), () -> service.draft(MISSING));
    }

    @Test
    void bobCannotWriteSaveOrDeleteAlicesDraft() {
        ProblemDraftChange change = new ProblemDraftChange(null, "Условие Б", null, null, null, null, null, null,
                null, null);

        assertSameRefusal(() -> service.write(alicesDraft.id(), alicesDraft.version(), change,
                        ProblemDraft.Editor.OWNER),
                () -> service.write(MISSING, 0, change, ProblemDraft.Editor.OWNER));
        assertSameRefusal(() -> service.save(alicesDraft.id(), alicesDraft.version(), ProblemDraftService.Kind.TEXT),
                () -> service.save(MISSING, 0, ProblemDraftService.Kind.TEXT));
        assertSameRefusal(() -> service.delete(alicesDraft.id(), alicesDraft.version()),
                () -> service.delete(MISSING, 0));

        assertThat(repository.find(alice.id(), alicesDraft.id())).contains(alicesDraft);
    }

    @Test
    void bobsDraftCannotTakeALineFromAlicesAssemblyDraft() {
        assertThatThrownBy(() -> service.create(new ProblemDraftContent(null, "Условие Б", "", whole(alicesPhoto),
                null, null, null, null, null, null), ProblemDraft.Editor.ASSISTANT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.drafts()).isEmpty();
    }

    private static PdfAssemblyOrder whole(AssemblyDraft photo) {
        return new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(photo.id(), 1, 1)));
    }

    /** Чужой и несуществующий отклоняются одним видом отказа с тем же текстом, кроме номера. */
    private static void assertSameRefusal(Runnable foreign, Runnable missing) {
        assertThatThrownBy(foreign::run).isInstanceOf(ProblemDraftNotFoundException.class)
                .hasMessageStartingWith("Черновика Задачи с идентификатором");
        assertThatThrownBy(missing::run).isInstanceOf(ProblemDraftNotFoundException.class)
                .hasMessageStartingWith("Черновика Задачи с идентификатором");
    }
}
