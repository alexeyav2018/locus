package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.file.FileType;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 2.2: хранение черновиков сборки — запись читается обратно, каждый
 * метод с владельцем отвечает только ему, а уборки удаляют черновики всех
 * и отдают одни имена файлов.
 *
 * Задача 2.1 problem-intake: черновик, на который ссылается строка сборки
 * Черновика Задачи, держится — его не удаляет ни удаление по номерам,
 * ни уборка по сроку.
 */
class AssemblyDraftRepositoryTest extends IntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private AssemblyDraftRepository drafts;

    @Autowired
    private ProblemDraftRepository problemDrafts;

    @Autowired
    private TestAccounts accounts;

    private UserId alice;
    private UserId bob;

    @BeforeEach
    void twoAdministrators() {
        alice = accounts.settled(Role.ADMINISTRATOR).id();
        bob = accounts.settled(Role.ADMINISTRATOR).id();
    }

    @AfterEach
    void removeProblemDrafts() {
        problemDrafts.deleteAll();
    }

    @Test
    void createdDraftReadsBackForItsOwner() {
        String file = UUID.randomUUID().toString();
        AssemblyDraftId id = drafts.create(alice, "сборник.pdf", AssemblyDraft.Kind.PDF, FileType.PDF, 300, file, NOW);

        assertThat(drafts.findByIds(alice, List.of(id))).containsExactly(new AssemblyDraft(
                id, alice, "сборник.pdf", AssemblyDraft.Kind.PDF, FileType.PDF, 300, file, NOW));
    }

    @Test
    void anotherOwnersDraftIsNeitherFoundNorDeleted() {
        AssemblyDraftId alices = draft(alice, NOW);
        AssemblyDraftId bobs = draft(bob, NOW);

        assertThat(drafts.findByIds(bob, List.of(alices, bobs))).extracting(AssemblyDraft::id).containsExactly(bobs);
        assertThat(drafts.deleteByIds(bob, List.of(alices))).as("чужой не удаляется").isEmpty();
        assertThat(drafts.findByIds(alice, List.of(alices))).hasSize(1);
    }

    /** locus-mcp 4.1: список владельца — только его черновики, в порядке загрузки, а не идентификаторов. */
    @Test
    void ownerListsOnlyOwnDraftsInUploadOrder() {
        AssemblyDraftId later = draft(alice, NOW);
        AssemblyDraftId earlier = draft(alice, NOW.minus(1, ChronoUnit.HOURS));
        draft(bob, NOW);

        assertThat(drafts.findByOwner(alice)).extracting(AssemblyDraft::id).containsExactly(earlier, later);
    }

    @Test
    void deletingByIdsReturnsTheFileNames() {
        String file = UUID.randomUUID().toString();
        AssemblyDraftId id = drafts.create(alice, "снимок.jpg", AssemblyDraft.Kind.IMAGE, FileType.JPEG, 1, file, NOW);

        assertThat(drafts.deleteByIds(alice, List.of(id))).containsExactly(file);
        assertThat(drafts.findByIds(alice, List.of(id))).isEmpty();
    }

    @Test
    void sweepByAgeTakesEveryOwnerButOnlyOldDrafts() {
        AssemblyDraftId alicesOld = draft(alice, NOW.minus(2, ChronoUnit.DAYS));
        AssemblyDraftId bobsOld = draft(bob, NOW.minus(2, ChronoUnit.DAYS));
        AssemblyDraftId fresh = draft(alice, NOW);

        drafts.deleteCreatedBefore(NOW.minus(1, ChronoUnit.DAYS));

        assertThat(drafts.findByIds(alice, List.of(alicesOld, fresh))).extracting(AssemblyDraft::id)
                .containsExactly(fresh);
        assertThat(drafts.findByIds(bob, List.of(bobsOld))).isEmpty();
    }

    @Test
    void sweepOfEverythingLeavesNothing() {
        AssemblyDraftId alices = draft(alice, NOW);
        AssemblyDraftId bobs = draft(bob, NOW);

        assertThat(drafts.deleteAll()).hasSizeGreaterThanOrEqualTo(2);

        assertThat(drafts.findByIds(alice, List.of(alices))).isEmpty();
        assertThat(drafts.findByIds(bob, List.of(bobs))).isEmpty();
    }

    @Test
    void draftHeldByAProblemDraftIsNotDeletedByIds() {
        AssemblyDraftId held = draft(alice, NOW);
        AssemblyDraftId free = draft(alice, NOW);
        holdBy(alice, held);

        drafts.deleteByIds(alice, List.of(held, free));

        assertThat(drafts.findByIds(alice, List.of(held, free))).extracting(AssemblyDraft::id).containsExactly(held);
    }

    @Test
    void draftHeldByAProblemDraftOutlivesTheSweepByAge() {
        AssemblyDraftId held = draft(alice, NOW.minus(2, ChronoUnit.DAYS));
        AssemblyDraftId free = draft(alice, NOW.minus(2, ChronoUnit.DAYS));
        holdBy(alice, held);

        drafts.deleteCreatedBefore(NOW.minus(1, ChronoUnit.DAYS));

        assertThat(drafts.findByIds(alice, List.of(held, free))).extracting(AssemblyDraft::id).containsExactly(held);
    }

    @Test
    void draftIsFreedOnceItsProblemDraftIsGone() {
        AssemblyDraftId held = draft(alice, NOW.minus(2, ChronoUnit.DAYS));
        holdBy(alice, held);
        problemDrafts.deleteAll();

        drafts.deleteCreatedBefore(NOW.minus(1, ChronoUnit.DAYS));

        assertThat(drafts.findByIds(alice, List.of(held))).isEmpty();
    }

    private void holdBy(UserId owner, AssemblyDraftId draft) {
        problemDrafts.create(owner, new ProblemDraftContent(null, "", "",
                        new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(draft, 1, 1))),
                        null, null, null, null, null, null),
                ProblemDraft.Editor.ASSISTANT, NOW);
    }

    private AssemblyDraftId draft(UserId owner, Instant createdAt) {
        return drafts.create(owner, "снимок.png", AssemblyDraft.Kind.IMAGE, FileType.PNG, 1,
                UUID.randomUUID().toString(), createdAt);
    }
}
