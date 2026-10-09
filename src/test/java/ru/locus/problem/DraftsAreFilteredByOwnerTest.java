package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.problem.PdfAssembly.PreviewSize.LARGE;
import static ru.locus.problem.PdfAssembly.PreviewSize.NORMAL;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Черновик сборки — собственность загрузившего (ADR-0044), и глазами
 * второго Администратора его нет: собрать из него нельзя, удалить —
 * тоже, а отказ тот же, что на несуществующий.
 *
 * Сценарии «Чужой черновик» и «Показ страницы чужого черновика» спеки. Здесь — через сервис, от имени двух
 * вошедших; адрес загрузки черновика появится с формой.
 */
class DraftsAreFilteredByOwnerTest extends IntegrationTest {

    @Autowired
    private AssemblyDraftService service;

    @Autowired
    private AssemblyDraftRepository drafts;

    @Autowired
    private AssemblyDraftProperties properties;

    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account alice;
    private TestAccounts.Account bob;
    private AssemblyDraft alicesDraft;

    @BeforeEach
    void aliceUploads() throws IOException {
        alice = accounts.settled(Role.ADMINISTRATOR);
        bob = accounts.settled(Role.ADMINISTRATOR);
        LoggedIn.as(alice);
        alicesDraft = service.upload("сборник.pdf", new ByteArrayInputStream(AssemblyDraftServiceTest.pdf(3)));
        LoggedIn.as(bob);
    }

    @AfterEach
    void loggedOut() {
        LoggedIn.nobody();
    }

    @Test
    void anotherAdministratorCannotAssembleFromIt() {
        assertThatThrownBy(() -> service.assemble(new PdfAssemblyOrder(List.of(
                new PdfAssemblyOrder.Line(alicesDraft.id(), 1, 2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("сборник.pdf");
    }

    @Test
    void anotherAdministratorCannotDiscardIt() {
        service.discard(List.of(alicesDraft.id()));

        assertThat(drafts.findByIds(alice.id(), List.of(alicesDraft.id()))).hasSize(1);
        assertThat(Path.of(properties.directory().toString(), alicesDraft.fileName())).exists();
    }

    /** Сценарий «Показ страницы чужого черновика»: ответ тот же, что у несуществующего. */
    @Test
    void anotherAdministratorCannotSeeItsPages() {
        assertThat(service.preview(alicesDraft.id(), 1, NORMAL)).isEmpty();
        assertThat(service.preview(new AssemblyDraftId(alicesDraft.id().value() + 1_000_000), 1, NORMAL)).isEmpty();
        assertThat(service.preview(alicesDraft.id(), 1, LARGE)).isEmpty();

        LoggedIn.as(alice);
        assertThat(service.preview(alicesDraft.id(), 1, NORMAL)).isPresent();
    }

    /** Сценарий «Учитель запрашивает показ». */
    @Test
    void teacherIsRefusedThePages() {
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.preview(alicesDraft.id(), 1, NORMAL))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anotherAdministratorDoesNotFindIt() {
        assertThat(drafts.findByIds(bob.id(), List.of(alicesDraft.id()))).isEmpty();
    }
}
