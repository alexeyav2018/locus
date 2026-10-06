package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
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
 * Черновик сборки — собственность загрузившего (ADR-0044), и глазами
 * второго Администратора его нет: собрать из него нельзя, удалить —
 * тоже, а отказ тот же, что на несуществующий.
 *
 * Сценарий «Чужой черновик» спеки. Здесь — через сервис, от имени двух
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

    @Test
    void anotherAdministratorDoesNotFindIt() {
        assertThat(drafts.findByIds(bob.id(), List.of(alicesDraft.id()))).isEmpty();
    }
}
