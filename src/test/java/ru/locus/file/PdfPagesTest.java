package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

/**
 * Страницы PDF картинками для страницы просмотра (ADR-0052): размеры,
 * которые обещает {@link PdfPages#outline}, совпадают с тем, что рисует
 * {@link PdfPages#render}, — иначе столбец прыгал бы при догрузке.
 */
class PdfPagesTest {

    @Test
    void outlineCountsPagesAndSizesTheFirstShown() throws IOException {
        byte[] pdf = pdf(30, PDRectangle.A4, 0);

        PdfOutline outline = PdfPages.outline(pdf).orElseThrow();

        assertThat(outline.pages()).isEqualTo(30);
        assertThat(outline.shown()).hasSize(PdfPages.SHOWN);
        assertThat(outline.shown().getFirst()).isEqualTo(sizeOf(PdfPages.render(pdf, 1).orElseThrow()));
    }

    @Test
    void shortPdfShowsEveryPage() throws IOException {
        PdfOutline outline = PdfPages.outline(pdf(3, PDRectangle.A4, 0)).orElseThrow();

        assertThat(outline.pages()).isEqualTo(3);
        assertThat(outline.shown()).hasSize(3);
    }

    @Test
    void a4IsRenderedToTheWidthLimit() throws IOException {
        byte[] pdf = pdf(1, PDRectangle.A4, 0);

        PdfOutline.PageSize size = sizeOf(PdfPages.render(pdf, 1).orElseThrow());

        assertThat(size.width()).isEqualTo(PdfPages.MAX_WIDTH);
        assertThat(size.height()).isGreaterThan(size.width());
    }

    @Test
    void smallPageIsNotBlownUpPastTheDpiLimit() throws IOException {
        byte[] pdf = pdf(1, new PDRectangle(144, 72), 0);

        PdfOutline.PageSize size = sizeOf(PdfPages.render(pdf, 1).orElseThrow());

        // два дюйма на один при 200 dpi
        assertThat(size).isEqualTo(new PdfOutline.PageSize(400, 200));
        assertThat(PdfPages.outline(pdf).orElseThrow().shown().getFirst()).isEqualTo(size);
    }

    @Test
    void turnedPageIsShownTurnedAndLimitedByItsShownWidth() throws IOException {
        byte[] pdf = pdf(1, PDRectangle.A4, 90);

        PdfOutline.PageSize size = sizeOf(PdfPages.render(pdf, 1).orElseThrow());

        assertThat(size.width()).isEqualTo(PdfPages.MAX_WIDTH);
        assertThat(size.width()).isGreaterThan(size.height());
        assertThat(PdfPages.outline(pdf).orElseThrow().shown().getFirst()).isEqualTo(size);
    }

    @Test
    void visibleAreaIsWhatIsShown() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setCropBox(new PDRectangle(0, 0, 72, 36));
            document.addPage(page);
            pdf = bytes(document);
        }

        PdfOutline.PageSize size = sizeOf(PdfPages.render(pdf, 1).orElseThrow());

        assertThat(size).isEqualTo(new PdfOutline.PageSize(200, 100));
        assertThat(PdfPages.outline(pdf).orElseThrow().shown().getFirst()).isEqualTo(size);
    }

    @Test
    void pageOutsideTheFileIsEmpty() throws IOException {
        byte[] pdf = pdf(2, PDRectangle.A4, 0);

        assertThat(PdfPages.render(pdf, 0)).isEmpty();
        assertThat(PdfPages.render(pdf, 3)).isEmpty();
        assertThat(PdfPages.render(pdf, 2)).isPresent();
    }

    @Test
    void garbageInsteadOfPdfIsEmpty() {
        byte[] garbage = "это не PDF".getBytes(StandardCharsets.UTF_8);

        assertThat(PdfPages.outline(garbage)).isEmpty();
        assertThat(PdfPages.render(garbage, 1)).isEmpty();
    }

    @Test
    void passwordProtectedPdfIsEmpty() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("владелец", "читатель",
                    new AccessPermission());
            document.protect(policy);
            pdf = bytes(document);
        }

        assertThat(PdfPages.outline(pdf)).isEmpty();
        assertThat(PdfPages.render(pdf, 1)).isEmpty();
    }

    static byte[] pdf(int pages, PDRectangle size, int rotation) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(size);
                page.setRotation(rotation);
                document.addPage(page);
            }
            return bytes(document);
        }
    }

    private static byte[] bytes(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    private static PdfOutline.PageSize sizeOf(byte[] jpeg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(image).as("картинка JPEG").isNotNull();
        return new PdfOutline.PageSize(image.getWidth(), image.getHeight());
    }
}
