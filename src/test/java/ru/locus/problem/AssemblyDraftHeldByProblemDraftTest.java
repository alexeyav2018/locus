package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.user.Role;

/**
 * Задача 2.4 problem-intake: живой Черновик Задачи держит свои Черновики
 * сборки сверх их суток, сам живёт неделю от последней правки и, уходя,
 * отпускает их той же уборкой; при старте не остаётся ни тех, ни других
 * (ADR-0059).
 */
class AssemblyDraftHeldByProblemDraftTest extends IntegrationTest {

    @Autowired
    private ProblemDraftService service;

    @Autowired
    private ProblemDraftRepository problemDrafts;

    @Autowired
    private AssemblyDraftService assemblyService;

    @Autowired
    private AssemblyDraftRepository assemblyDrafts;

    @Autowired
    private AssemblyDraftProperties properties;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestClock clock;

    @Autowired
    @Qualifier("assemblyDraftsClearedOnStartup")
    private ApplicationRunner startupCleanup;

    private TestAccounts.Account administrator;

    @BeforeEach
    void administratorLoggedIn() {
        administrator = accounts.settled(Role.ADMINISTRATOR);
        LoggedIn.as(administrator);
    }

    @AfterEach
    void cleanUp() {
        clock.reset();
        problemDrafts.deleteAll();
        LoggedIn.nobody();
    }

    @Test
    void liveProblemDraftHoldsAnAssemblyDraftPastItsDay() throws IOException {
        clock.shift(Duration.ofHours(-25));
        AssemblyDraft photo = photo();
        clock.reset();
        holdBy(photo);

        service.sweep();
        assemblyService.sweep();

        assertThat(exists(photo)).isTrue();
        assertThat(fileOf(photo)).exists();
    }

    @Test
    void problemDraftUnchangedPastItsWeekLeavesAndTakesItsAssemblyDraft() throws IOException {
        clock.shift(Duration.ofDays(-8));
        AssemblyDraft photo = photo();
        ProblemDraftId draft = holdBy(photo);
        clock.reset();

        service.sweep();

        assertThat(problemDrafts.find(administrator.id(), draft)).isEmpty();
        assertThat(exists(photo)).as("отпущенный Черновик сборки старше суток уходит той же уборкой").isFalse();
        assertThat(fileOf(photo)).doesNotExist();
    }

    @Test
    void problemDraftChangedRecentlyStays() throws IOException {
        clock.shift(Duration.ofDays(-8));
        AssemblyDraft photo = photo();
        ProblemDraftId draft = holdBy(photo);
        clock.reset();
        clock.shift(Duration.ofDays(-1));
        ProblemDraft stored = problemDrafts.find(administrator.id(), draft).orElseThrow();
        problemDrafts.write(administrator.id(), draft, stored.version(), stored.content(), ProblemDraft.Editor.OWNER,
                clock.instant());
        clock.reset();

        service.sweep();

        assertThat(problemDrafts.find(administrator.id(), draft)).isPresent();
        assertThat(exists(photo)).isTrue();
    }

    @Test
    void startupLeavesNeitherProblemDraftsNorAssemblyDrafts() throws Exception {
        AssemblyDraft photo = photo();
        ProblemDraftId draft = holdBy(photo);

        startupCleanup.run(new DefaultApplicationArguments());

        assertThat(problemDrafts.find(administrator.id(), draft)).isEmpty();
        assertThat(exists(photo)).isFalse();
        assertThat(fileOf(photo)).doesNotExist();
    }

    private AssemblyDraft photo() throws IOException {
        return assemblyService.upload("снимок.png", new ByteArrayInputStream(AssemblyDraftServiceTest.png()));
    }

    private ProblemDraftId holdBy(AssemblyDraft photo) {
        return problemDrafts.create(administrator.id(), new ProblemDraftContent(null, "", "",
                        new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(photo.id(), 1, 1))),
                        null, null, null, null, null, null),
                ProblemDraft.Editor.ASSISTANT, clock.instant());
    }

    private boolean exists(AssemblyDraft draft) {
        return !assemblyDrafts.findByIds(administrator.id(), List.of(draft.id())).isEmpty();
    }

    private Path fileOf(AssemblyDraft draft) {
        return properties.directory().resolve(draft.fileName());
    }
}
