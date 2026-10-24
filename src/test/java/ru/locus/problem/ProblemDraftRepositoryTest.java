package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 1.3: хранение Черновиков Задачи — запись читается обратно всеми
 * частями в их порядке, версия растёт с каждой записью, а запись по старой
 * версии ничего не меняет.
 *
 * Черновики, оставленные тестом, убираются после каждого: строка сборки
 * держит Черновик сборки ключом, и уборки Черновиков сборки в соседних
 * тестах упали бы на ней.
 */
class ProblemDraftRepositoryTest extends IntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private ProblemDraftRepository drafts;

    @Autowired
    private AssemblyDraftRepository assemblyDrafts;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId alice;
    private UserId bob;

    @BeforeEach
    void twoAdministrators() {
        alice = accounts.settled(Role.ADMINISTRATOR).id();
        bob = accounts.settled(Role.ADMINISTRATOR).id();
    }

    @AfterEach
    void removeDrafts() {
        drafts.deleteAll();
    }

    @Test
    void createdDraftReadsBackWithEveryPartInOrder() {
        AssemblyDraftId photo = photo(alice);
        AssemblyDraftId book = photo(alice);
        TaxonomyNodeId second = library.topic();
        TaxonomyNodeId first = library.topic();
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();
        ProblemDraftContent content = new ProblemDraftContent(
                ExamPart.SECOND,
                "$\\log_2 ⟦8⟧ = 3$",
                "Решение ⟦так⟧",
                new PdfAssemblyOrder(List.of(
                        new PdfAssemblyOrder.Line(photo, 1, 1, new CropFrame(0.1, 0.2, 0.5, 0.3)),
                        new PdfAssemblyOrder.Line(book, 2, 4))),
                new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(photo, 1, 1, new CropFrame(0, 0.5, 1, 0.5)))),
                List.of("8 похоже на 9", "не видно слова"),
                List.of(second, first),
                List.of(method),
                List.of(characteristic),
                List.of("Метод рационализации", "Замена переменной"));

        ProblemDraftId id = drafts.create(alice, content, ProblemDraft.Editor.ASSISTANT, NOW);

        assertThat(drafts.find(alice, id)).hasValue(
                new ProblemDraft(id, alice, content, 0, ProblemDraft.Editor.ASSISTANT, NOW, NOW));
    }

    @Test
    void emptyDraftReadsBackEmpty() {
        ProblemDraftContent empty = empty();

        ProblemDraftId id = drafts.create(alice, empty, ProblemDraft.Editor.ASSISTANT, NOW);

        assertThat(drafts.find(alice, id)).get().extracting(ProblemDraft::content).isEqualTo(empty);
    }

    @Test
    void writeWithTheReadVersionReplacesTheContentAndRaisesTheVersion() {
        AssemblyDraftId photo = photo(alice);
        ProblemDraftId id = drafts.create(alice, withLine(photo, "старое"), ProblemDraft.Editor.ASSISTANT, NOW);
        Instant later = NOW.plus(1, ChronoUnit.HOURS);
        ProblemDraftContent next = new ProblemDraftContent(ExamPart.FIRST, "новое", "", null, null, null,
                List.of(library.topic()), null, null, null);

        OptionalLong version = drafts.write(alice, id, 0, next, ProblemDraft.Editor.OWNER, later);

        assertThat(version).hasValue(1);
        assertThat(drafts.find(alice, id)).hasValue(
                new ProblemDraft(id, alice, next, 1, ProblemDraft.Editor.OWNER, later, NOW));
        assertThat(drafts.write(alice, id, 1, next, ProblemDraft.Editor.ASSISTANT, later)).hasValue(2);
    }

    @Test
    void writeWithAnOldVersionChangesNothing() {
        ProblemDraftId id = drafts.create(alice, withLine(photo(alice), "первое"), ProblemDraft.Editor.ASSISTANT, NOW);
        drafts.write(alice, id, 0, withLine(photo(alice), "учитель"), ProblemDraft.Editor.OWNER, NOW);
        ProblemDraft written = drafts.find(alice, id).orElseThrow();

        OptionalLong stale = drafts.write(alice, id, 0, empty(), ProblemDraft.Editor.ASSISTANT, NOW.plusSeconds(60));

        assertThat(stale).isEmpty();
        assertThat(drafts.find(alice, id)).hasValue(written);
    }

    @Test
    void anotherOwnersDraftIsNeitherFoundNorWrittenNorDeleted() {
        ProblemDraftId alices = drafts.create(alice, withLine(photo(alice), "А"), ProblemDraft.Editor.ASSISTANT, NOW);
        ProblemDraft before = drafts.find(alice, alices).orElseThrow();

        assertThat(drafts.find(bob, alices)).isEmpty();
        assertThat(drafts.findByOwner(bob)).isEmpty();
        assertThat(drafts.write(bob, alices, 0, empty(), ProblemDraft.Editor.OWNER, NOW)).isEmpty();
        assertThat(drafts.delete(bob, alices, 0)).isFalse();
        assertThat(drafts.find(alice, alices)).hasValue(before);
    }

    @Test
    void ownerListsOwnDraftsInCreationOrder() {
        ProblemDraftId later = drafts.create(alice, empty(), ProblemDraft.Editor.ASSISTANT, NOW);
        ProblemDraftId earlier = drafts.create(alice, empty(), ProblemDraft.Editor.ASSISTANT, NOW.minusSeconds(60));
        drafts.create(bob, empty(), ProblemDraft.Editor.ASSISTANT, NOW);

        assertThat(drafts.findByOwner(alice)).extracting(ProblemDraft::id).containsExactly(earlier, later);
    }

    @Test
    void deleteNeedsTheReadVersion() {
        ProblemDraftId id = drafts.create(alice, withLine(photo(alice), "у"), ProblemDraft.Editor.ASSISTANT, NOW);
        drafts.write(alice, id, 0, empty(), ProblemDraft.Editor.OWNER, NOW);

        assertThat(drafts.delete(alice, id, 0)).as("старая версия не удаляет").isFalse();
        assertThat(drafts.delete(alice, id, 1)).isTrue();
        assertThat(drafts.find(alice, id)).isEmpty();
    }

    @Test
    void sweepByAgeTakesEveryOwnerButCountsFromTheLastChange() {
        Instant old = NOW.minus(10, ChronoUnit.DAYS);
        ProblemDraftId alicesOld = drafts.create(alice, empty(), ProblemDraft.Editor.ASSISTANT, old);
        ProblemDraftId bobsOld = drafts.create(bob, empty(), ProblemDraft.Editor.ASSISTANT, old);
        ProblemDraftId returnedTo = drafts.create(alice, empty(), ProblemDraft.Editor.ASSISTANT, old);
        drafts.write(alice, returnedTo, 0, empty(), ProblemDraft.Editor.OWNER, NOW);

        assertThat(drafts.deleteChangedBefore(NOW.minus(7, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(2);

        assertThat(drafts.find(alice, alicesOld)).isEmpty();
        assertThat(drafts.find(bob, bobsOld)).isEmpty();
        assertThat(drafts.find(alice, returnedTo)).as("к нему вернулись").isPresent();
    }

    @Test
    void sweepOfEverythingLeavesNothing() {
        ProblemDraftId alices = drafts.create(alice, withLine(photo(alice), "у"), ProblemDraft.Editor.ASSISTANT, NOW);
        ProblemDraftId bobs = drafts.create(bob, empty(), ProblemDraft.Editor.ASSISTANT, NOW);

        assertThat(drafts.deleteAll()).isGreaterThanOrEqualTo(2);

        assertThat(drafts.find(alice, alices)).isEmpty();
        assertThat(drafts.find(bob, bobs)).isEmpty();
    }

    private ProblemDraftContent withLine(AssemblyDraftId photo, String condition) {
        return new ProblemDraftContent(null, condition, "", new PdfAssemblyOrder(List.of(
                new PdfAssemblyOrder.Line(photo, 1, 1))), null, null, null, null, null, null);
    }

    private static ProblemDraftContent empty() {
        return new ProblemDraftContent(null, "", "", null, null, null, null, null, null, null);
    }

    private AssemblyDraftId photo(UserId owner) {
        return assemblyDrafts.create(owner, "лист.jpg", AssemblyDraft.Kind.IMAGE, FileType.JPEG, 1,
                UUID.randomUUID().toString(), NOW);
    }
}
