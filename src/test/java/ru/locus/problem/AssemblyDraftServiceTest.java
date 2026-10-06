package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.file.FileType;
import ru.locus.user.Role;

/**
 * Задачи 2.4–2.6: черновик сборки — загрузка с распознаванием исходника,
 * сборка только из своих черновиков, удаление после фиксации и уборка
 * по сроку и при старте (ADR-0041).
 */
class AssemblyDraftServiceTest extends IntegrationTest {

    @Autowired
    private AssemblyDraftService service;

    @Autowired
    private AssemblyDraftRepository drafts;

    @Autowired
    private AssemblyDraftProperties properties;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestClock clock;

    @Autowired
    private TransactionTemplate transaction;

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
        LoggedIn.nobody();
    }

    @Test
    void pdfBecomesADraftWithItsPageCount() throws IOException {
        AssemblyDraft draft = service.upload("сборник.pdf", stream(pdf(5)));

        assertThat(draft.kind()).isEqualTo(AssemblyDraft.Kind.PDF);
        assertThat(draft.contentType()).isEqualTo(FileType.PDF);
        assertThat(draft.pageCount()).isEqualTo(5);
        assertThat(draft.originalName()).isEqualTo("сборник.pdf");
        assertThat(draft.owner()).isEqualTo(administrator.id());
        assertThat(fileOf(draft)).exists();
    }

    @Test
    void imageBecomesADraftOfOnePage() throws IOException {
        AssemblyDraft draft = service.upload("снимок.png", stream(png()));

        assertThat(draft.kind()).isEqualTo(AssemblyDraft.Kind.IMAGE);
        assertThat(draft.contentType()).isEqualTo(FileType.PNG);
        assertThat(draft.pageCount()).isEqualTo(1);
    }

    @Test
    void textFileIsRefusedAndLeavesNothing() throws IOException {
        long filesBefore = filesInDirectory();

        assertThatThrownBy(() -> service.upload("заметки.txt",
                stream("просто текст".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("заметки.txt")
                .hasMessageContaining("JPEG и PNG и файлы PDF");
        assertThat(filesInDirectory()).as("отклонённый исходник с диска убран").isEqualTo(filesBefore);
    }

    @Test
    void passwordProtectedPdfIsRefusedWithAnExplanation() throws IOException {
        assertThatThrownBy(() -> service.upload("закрытый.pdf", stream(protectedPdf())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("закрыт паролем");
    }

    @Test
    void teacherCannotUpload() throws IOException {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        byte[] png = png();

        assertThatThrownBy(() -> service.upload("снимок.png", stream(png)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownDraftAssembles() throws IOException {
        AssemblyDraft book = service.upload("сборник.pdf", stream(pdf(5)));
        AssemblyDraft photo = service.upload("снимок.png", stream(png()));

        byte[] result = service.assemble(new PdfAssemblyOrder(List.of(
                new PdfAssemblyOrder.Line(book.id(), 2, 3),
                new PdfAssemblyOrder.Line(photo.id(), 1, 1))));

        try (PDDocument assembled = Loader.loadPDF(result)) {
            assertThat(assembled.getNumberOfPages()).isEqualTo(3);
        }
    }

    @Test
    void anotherOwnersDraftIsRefusedLikeAMissingOne() throws IOException {
        AssemblyDraft alices = service.upload("снимок.png", stream(png()));
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        String foreign = refusal(new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(alices.id(), 1, 1))));
        String missing = refusal(new PdfAssemblyOrder(List.of(
                new PdfAssemblyOrder.Line(new AssemblyDraftId(Long.MAX_VALUE), 1, 1))));

        assertThat(foreign).isEqualTo(missing);
    }

    @Test
    void rangeBeyondThePagesNamesTheSourceAndItsPageCount() throws IOException {
        AssemblyDraft book = service.upload("сборник.pdf", stream(pdf(5)));

        assertThat(refusal(new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(book.id(), 4, 7)))))
                .contains("сборник.pdf").contains("5");
    }

    @Test
    void discardRemovesRowAndFileAfterCommit() throws IOException {
        AssemblyDraft draft = service.upload("снимок.png", stream(png()));

        transaction.executeWithoutResult(status -> {
            service.discard(List.of(draft.id()));
            assertThat(fileOf(draft)).as("до фиксации файл на месте").exists();
        });

        assertThat(drafts.findByIds(administrator.id(), List.of(draft.id()))).isEmpty();
        assertThat(fileOf(draft)).doesNotExist();
    }

    @Test
    void rolledBackDiscardLeavesTheDraft() throws IOException {
        AssemblyDraft draft = service.upload("снимок.png", stream(png()));

        transaction.executeWithoutResult(status -> {
            service.discard(List.of(draft.id()));
            status.setRollbackOnly();
        });

        assertThat(drafts.findByIds(administrator.id(), List.of(draft.id()))).hasSize(1);
        assertThat(fileOf(draft)).exists();
    }

    @Test
    void sweepRemovesOldDraftsAndKeepsFreshOnes() throws IOException {
        clock.shift(Duration.ofHours(-25));
        AssemblyDraft old = service.upload("старый.png", stream(png()));
        clock.reset();
        AssemblyDraft fresh = service.upload("свежий.png", stream(png()));

        service.sweep();

        assertThat(drafts.findByIds(administrator.id(), List.of(old.id(), fresh.id())))
                .extracting(AssemblyDraft::id).containsExactly(fresh.id());
        assertThat(fileOf(old)).doesNotExist();
        assertThat(fileOf(fresh)).exists();
    }

    @Test
    void startupCleanupRemovesEveryDraftAndStrayFile() throws Exception {
        AssemblyDraft draft = service.upload("снимок.png", stream(png()));
        Path stray = properties.directory().resolve(UUID.randomUUID().toString());
        Files.write(stray, new byte[] {1, 2, 3});

        startupCleanup.run(new DefaultApplicationArguments());

        assertThat(drafts.findByIds(administrator.id(), List.of(draft.id()))).isEmpty();
        assertThat(fileOf(draft)).doesNotExist();
        assertThat(stray).as("файл без строки").doesNotExist();
    }

    private String refusal(PdfAssemblyOrder order) {
        try {
            service.assemble(order);
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("сборка должна была быть отклонена");
    }

    private Path fileOf(AssemblyDraft draft) {
        return properties.directory().resolve(draft.fileName());
    }

    private long filesInDirectory() throws IOException {
        Files.createDirectories(properties.directory());
        try (var files = Files.list(properties.directory())) {
            return files.count();
        }
    }

    private static ByteArrayInputStream stream(byte[] content) {
        return new ByteArrayInputStream(content);
    }

    static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] protectedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("владелец", "читатель", new AccessPermission());
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
