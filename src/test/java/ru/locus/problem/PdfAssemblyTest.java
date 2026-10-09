package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static ru.locus.problem.PdfAssembly.PreviewSize.LARGE;
import static ru.locus.problem.PdfAssembly.PreviewSize.NORMAL;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Сборка PDF Задачи (ADR-0044): требования «PDF слота Задачи собирается
 * из картинок и страниц PDF» и «Результат содержит только взятые страницы».
 *
 * Модульный тест без Spring: {@link PdfAssembly} — чистая функция над
 * файлами, и самое хрупкое в ней — что утекает в результат со страниц
 * сборника — проверяется прямо по объектам собранного PDF, а не по
 * впечатлению от просмотрщика.
 *
 * Сборники строятся здесь же, PDFBox, — так, как их строят генераторы,
 * на которых перенос страницы обычно и протекает: один словарь ресурсов
 * на все страницы, формы с тем же общим словарём, ссылки на соседние
 * страницы.
 */
class PdfAssemblyTest {

    /** Один шрифт на все надписи сборника: в ресурсах страницы он один под одним именем. */
    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private final PdfAssembly assembly = new PdfAssembly();

    @TempDir
    Path directory;

    // --- 1.2 Диапазон -------------------------------------------------------

    @Test
    void rangeStartsAtTheFirstPage() {
        assertThatThrownBy(() -> new PdfAssemblyPart.Pages(directory, "сборник.pdf", 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сборник.pdf");
    }

    @Test
    void rangeStartIsNotAfterItsEnd() {
        assertThatThrownBy(() -> new PdfAssemblyPart.Pages(directory, "сборник.pdf", 5, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сборник.pdf");
    }

    @Test
    void rangeBeyondTheLastPageNamesTheSourceAndItsPageCount() throws IOException {
        Path book = sharedResourcesBook(10);

        assertThatThrownBy(() -> assembly.assemble(List.of(
                new PdfAssemblyPart.Pages(book, "сборник.pdf", 9, 11))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сборник.pdf")
                .hasMessageContaining("10 стр.");
    }

    @Test
    void lastPageIsWithinTheRange() throws IOException {
        Path book = sharedResourcesBook(10);

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Pages(book, "сборник.pdf", 10, 10)));

        assertThat(imageWidths(result)).containsExactly(width(10));
    }

    @Test
    void nothingToAssembleIsRefused() {
        assertThatThrownBy(() -> assembly.assemble(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notAPdfIsRefused() throws IOException {
        Path text = Files.writeString(directory.resolve("заметки.txt"), "не PDF");

        assertThatThrownBy(() -> assembly.pageCount(text, "заметки.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("заметки.txt")
                .cause().isInstanceOf(IOException.class);
    }

    @Test
    void pageCountOfABook() throws IOException {
        assertThat(assembly.pageCount(sharedResourcesBook(12), "сборник.pdf")).isEqualTo(12);
    }

    // --- 1.3 Общий словарь ресурсов ----------------------------------------

    /**
     * Признак готовности карточки: из 300-страничного PDF со страницами
     * 47–49 — ровно три страницы, ровно три картинки, и объём соразмерен
     * взятым страницам, а не исходнику.
     */
    @Test
    void threePagesOfAThreeHundredPageBookCarryOnlyTheirOwnImages() throws IOException {
        Path book = sharedResourcesBook(300);

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Pages(book, "сборник.pdf", 47, 49)));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
        }
        // Ширина картинки — номер её страницы: так видно, что взяты именно
        // 47–49 и именно в этом порядке.
        assertThat(pageImageWidths(result)).containsExactly(width(47), width(48), width(49));
        assertThat(imageWidths(result)).containsExactlyInAnyOrder(width(47), width(48), width(49));

        long perPage = Files.size(book) / 300;
        assertThat((long) result.length).isLessThan(perPage * 3 * 2 + 20_000);
    }

    // --- 1.4 Прочие утечки --------------------------------------------------

    @Test
    void fontsOfPagesNotTakenStayBehind() throws IOException {
        Path book = directory.resolve("шрифты.pdf");
        try (PDDocument document = new PDDocument()) {
            PDResources shared = new PDResources();
            List<Standard14Fonts.FontName> fonts = List.of(
                    Standard14Fonts.FontName.HELVETICA,
                    Standard14Fonts.FontName.TIMES_ROMAN,
                    Standard14Fonts.FontName.COURIER);
            for (Standard14Fonts.FontName font : fonts) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setResources(shared);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(font), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText("x");
                    content.endText();
                }
            }
            document.save(book.toFile());
        }

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Pages(book, "шрифты.pdf", 2, 2)));

        assertThat(baseFonts(result)).containsExactly("Times-Roman");
    }

    @Test
    void linkToAPageNotTakenDoesNotDragItAlong() throws IOException {
        Path book = directory.resolve("ссылки.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            PDPage second = new PDPage(PDRectangle.A4);
            document.addPage(first);
            document.addPage(second);
            drawImage(document, second, noise(width(2), 200, 2));

            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(second);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 72, 100, 20));
            link.setDestination(destination);
            first.getAnnotations().add(link);
            document.save(book.toFile());
        }

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Pages(book, "ссылки.pdf", 1, 1)));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getPage(0).getAnnotations()).isEmpty();
        }
        assertThat(imageWidths(result)).isEmpty();
    }

    @Test
    void formSharingTheBookResourcesIsPrunedToo() throws IOException {
        Path book = directory.resolve("формы.pdf");
        try (PDDocument document = new PDDocument()) {
            PDResources shared = new PDResources();
            for (int number = 1; number <= 5; number++) {
                PDImageXObject image = LosslessFactory.createFromImage(document, noise(width(number), 120, number));
                COSName imageName = shared.add(image);

                // Форма рисует картинку своей страницы, а ресурсами служит
                // тот же общий словарь — так делают некоторые генераторы.
                PDFormXObject form = new PDFormXObject(document);
                form.setBBox(PDRectangle.A4);
                form.setResources(shared);
                try (var out = form.getContentStream().createOutputStream()) {
                    out.write(("q 100 0 0 100 0 0 cm /" + imageName.getName() + " Do Q").getBytes());
                }
                COSName formName = shared.add(form);

                PDPage page = new PDPage(PDRectangle.A4);
                page.setResources(shared);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.appendRawCommands("/" + formName.getName() + " Do\n");
                }
            }
            document.save(book.toFile());
        }

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Pages(book, "формы.pdf", 3, 3)));

        assertThat(imageWidths(result)).containsExactly(width(3));
    }

    // --- 1.5 Картинки -------------------------------------------------------

    /** Признак готовности карточки: из трёх картинок — три страницы в заданном порядке. */
    @Test
    void threeImagesMakeThreePagesInTheGivenOrder() throws IOException {
        Path first = png("первая.png", 300, 200);
        Path second = png("вторая.png", 120, 500);
        Path third = png("третья.png", 640, 480);

        byte[] result = assembly.assemble(List.of(
                new PdfAssemblyPart.Image(second, "вторая.png"),
                new PdfAssemblyPart.Image(first, "первая.png"),
                new PdfAssemblyPart.Image(third, "третья.png")));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
            // Страница размером по картинке, без подгонки под лист.
            assertThat(size(document.getPage(0))).containsExactly(120f, 500f);
            assertThat(size(document.getPage(1))).containsExactly(300f, 200f);
            assertThat(size(document.getPage(2))).containsExactly(640f, 480f);
        }
    }

    /** JPEG вкладывается байт в байт: ни пережатия до предела сканов, ни перекодирования. */
    @Test
    void jpegIsEmbeddedAsIs() throws IOException {
        Path photo = directory.resolve("снимок.jpg");
        ImageIO.write(noise(400, 300, 7), "jpg", photo.toFile());

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Image(photo, "снимок.jpg")));

        List<byte[]> embedded = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(result)) {
            for (COSStream image : images(document)) {
                try (InputStream raw = image.createRawInputStream()) {
                    embedded.add(raw.readAllBytes());
                }
            }
        }
        assertThat(embedded).hasSize(1);
        assertThat(embedded.get(0)).isEqualTo(Files.readAllBytes(photo));
    }

    /**
     * Снимок повёрнутой камерой: пиксели лежат горизонтально (600 × 400),
     * ориентация задана сведениями о съёмке. Страница стоит вертикально.
     */
    @Test
    void photoTakenWithARotatedCameraMakesAnUprightPage() throws IOException {
        Path photo = directory.resolve("повёрнутый.jpg");
        try (InputStream in = PdfAssemblyTest.class.getResourceAsStream("/ru/locus/file/rotated.jpg")) {
            Files.write(photo, in.readAllBytes());
        }

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Image(photo, "повёрнутый.jpg")));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(size(document.getPage(0))).containsExactly(400f, 600f);
        }
    }

    /**
     * Снимок с Android-планшета: JFIF с каналами, пронумерованными от нуля.
     * Встроенный чтец Java не строит по нему метаданные, сведений о съёмке
     * в нём нет — картинка ложится как хранится и вкладывается байт в байт.
     */
    @Test
    void jpegWithChannelsNumberedFromZeroIsTakenWhole() throws IOException {
        Path photo = directory.resolve("планшет.jpeg");
        Files.write(photo, channelsNumberedFromZero(jpeg(noise(400, 300, 11))));

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Image(photo, "планшет.jpeg")));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(size(document.getPage(0))).containsExactly(400f, 300f);
            List<COSStream> embedded = images(document);
            assertThat(embedded).hasSize(1);
            try (InputStream raw = embedded.get(0).createRawInputStream()) {
                assertThat(raw.readAllBytes()).isEqualTo(Files.readAllBytes(photo));
            }
        }
    }

    /**
     * Испорченный JPEG не вкладывается: отказ не держится на чтении сведений
     * о съёмке и несёт исходную причину — для журнала, не для экрана.
     */
    @Test
    void brokenJpegIsRefused() throws IOException {
        byte[] broken = jpeg(noise(400, 300, 13));
        Random random = new Random(17);
        for (int at = 4; at < broken.length; at++) {
            broken[at] = (byte) random.nextInt(256);
        }
        Path photo = Files.write(directory.resolve("битый.jpg"), broken);

        assertThatThrownBy(() -> assembly.assemble(List.of(new PdfAssemblyPart.Image(photo, "битый.jpg"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("битый.jpg")
                .cause().isInstanceOf(IOException.class);
    }


    @Test
    void neitherJpegNorPngIsRefused() throws IOException {
        Path gif = directory.resolve("анимация.gif");
        ImageIO.write(noise(10, 10, 1), "gif", gif.toFile());

        assertThatThrownBy(() -> assembly.assemble(List.of(new PdfAssemblyPart.Image(gif, "анимация.gif"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("анимация.gif");
    }

    // --- 1.6 Источники вперемешку -------------------------------------------

    @Test
    void mixedSourcesKeepTheGivenOrder() throws IOException {
        Path book = sharedResourcesBook(20);
        Path picture = png("картинка.png", width(100), 50);

        byte[] result = assembly.assemble(List.of(
                new PdfAssemblyPart.Pages(book, "сборник.pdf", 5, 6),
                new PdfAssemblyPart.Image(picture, "картинка.png"),
                new PdfAssemblyPart.Pages(book, "сборник.pdf", 18, 18)));

        assertThat(pageImageWidths(result)).containsExactly(width(5), width(6), width(100), width(18));
    }

    // --- problem-pdf-crop 2.1 Векторный кусок -------------------------------

    /**
     * Кусок векторной страницы остаётся векторным: текст извлекается,
     * страница размером с рамку, ресурсы других страниц не попали.
     */
    @Test
    void vectorPieceKeepsItsTextAndTakesTheSizeOfTheFrame() throws IOException {
        Path book = textBook(5);
        CropFrame frame = new CropFrame(0.1, 0.4, 0.8, 0.2);

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Piece(book, "сборник.pdf", 3, frame)));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            PDPage page = document.getPage(0);
            PDRectangle expected = frame.on(PDRectangle.A4, 0);
            assertThat(page.getMediaBox().getWidth()).isCloseTo(expected.getWidth(), within(0.01f));
            assertThat(page.getMediaBox().getHeight()).isCloseTo(expected.getHeight(), within(0.01f));
            assertThat(page.getCropBox().getLowerLeftY()).isCloseTo(expected.getLowerLeftY(), within(0.01f));
            assertThat(visibleText(page)).contains("Problem 3").doesNotContain("Header 3");
        }
        assertThat(imageWidths(result)).containsExactly(width(3));
        assertThat(baseFonts(result)).containsExactly("Helvetica");
    }

    @Test
    void pieceBeyondTheLastPageNamesTheSourceAndItsPageCount() throws IOException {
        Path book = textBook(2);

        assertThatThrownBy(() -> assembly.assemble(List.of(
                new PdfAssemblyPart.Piece(book, "сборник.pdf", 3, new CropFrame(0, 0, 1, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сборник.pdf")
                .hasMessageContaining("2 стр.");
    }

    // --- problem-pdf-crop 2.2 Скан ------------------------------------------

    /** Скан даёт страницу с одной картинкой размером с кусок в 300 dpi и без текста. */
    @Test
    void scanPieceIsOneImageOfTheFrameSize() throws IOException {
        Path scan = scan("скан.pdf");
        CropFrame frame = new CropFrame(0.25, 0.5, 0.5, 0.25);

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Piece(scan, "скан.pdf", 1, frame)));

        try (PDDocument document = Loader.loadPDF(result)) {
            PDPage page = document.getPage(0);
            // A4 в 300 dpi — 2480 × 3508 пикселей; кусок — половина ширины, четверть высоты.
            List<Integer> widths = pageImageWidths(result);
            assertThat(widths).hasSize(1);
            assertThat(widths.get(0)).isCloseTo(1240, within(2));
            assertThat(page.getMediaBox().getWidth()).isCloseTo(PDRectangle.A4.getWidth() / 2, within(1f));
            assertThat(page.getMediaBox().getHeight()).isCloseTo(PDRectangle.A4.getHeight() / 4, within(1f));
            assertThat(new PDFTextStripper().getText(document)).isBlank();
        }
    }

    @Test
    void pageDrawingOnlyOneImageIsAScan() throws IOException {
        Path scan = scan("скан.pdf");
        try (PDDocument document = Loader.loadPDF(scan.toFile())) {
            assertThat(PdfAssembly.isScan(document.getPage(0))).isTrue();
        }
    }

    @Test
    void vectorPageWithAnImageAndTextIsNotAScan() throws IOException {
        Path book = textBook(1);
        try (PDDocument document = Loader.loadPDF(book.toFile())) {
            assertThat(PdfAssembly.isScan(document.getPage(0))).isFalse();
        }
    }

    @Test
    void pageDrawingAFormIsNotAScan() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFormXObject form = new PDFormXObject(document);
            form.setBBox(PDRectangle.A4);
            PDPage page = new PDPage(PDRectangle.A4);
            page.setResources(new PDResources());
            document.addPage(page);
            COSName name = page.getResources().add(form);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.appendRawCommands("/" + name.getName() + " Do\n");
            }
            assertThat(PdfAssembly.isScan(page)).isFalse();
        }
    }

    // --- problem-pdf-crop 2.3 Кусок картинки --------------------------------

    @Test
    void pieceOfAPictureIsItsOwnPixels() throws IOException {
        Path picture = png("картинка.png", 400, 300);

        byte[] result = assembly.assemble(List.of(
                new PdfAssemblyPart.Image(picture, "картинка.png", new CropFrame(0.25, 0.5, 0.5, 0.5))));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(size(document.getPage(0))).containsExactly(200f, 150f);
        }
        assertThat(pageImageWidths(result)).containsExactly(200);
    }

    /**
     * Снимок с ориентацией 6: пиксели лежат 600 × 400, видит Администратор
     * 400 × 600. Левая половина видимого — 200 × 600.
     */
    @Test
    void photoTakenWithARotatedCameraIsCutAsItIsSeen() throws IOException {
        Path photo = directory.resolve("повёрнутый.jpg");
        try (InputStream in = PdfAssemblyTest.class.getResourceAsStream("/ru/locus/file/rotated.jpg")) {
            Files.write(photo, in.readAllBytes());
        }

        byte[] result = assembly.assemble(List.of(
                new PdfAssemblyPart.Image(photo, "повёрнутый.jpg", new CropFrame(0, 0, 0.5, 1))));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(size(document.getPage(0))).containsExactly(200f, 600f);
        }
        assertThat(pageImageWidths(result)).containsExactly(200);
    }

    // --- problem-pdf-crop 2.4 Сквозные случаи -------------------------------

    /**
     * Признак готовности: две страницы сборника с задачей посреди каждой
     * дают PDF из двух обрезанных страниц — без остальных страниц и их
     * ресурсов.
     */
    @Test
    void twoPagesOfABookWithAProblemInTheMiddleMakeTwoCutPages() throws IOException {
        Path book = textBook(30);
        CropFrame middle = new CropFrame(0.05, 0.4, 0.9, 0.2);

        byte[] result = assembly.assemble(List.of(
                new PdfAssemblyPart.Piece(book, "сборник.pdf", 12, middle),
                new PdfAssemblyPart.Piece(book, "сборник.pdf", 13, middle)));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(visibleText(document.getPage(0))).contains("Problem 12").doesNotContain("Header 12");
            assertThat(visibleText(document.getPage(1))).contains("Problem 13").doesNotContain("Header 13");
        }
        assertThat(pageImageWidths(result)).containsExactly(width(12), width(13));
        assertThat(imageWidths(result)).containsExactlyInAnyOrder(width(12), width(13));
    }

    /**
     * Повёрнутая страница: рамка поставлена на показанной (повёрнутой)
     * картинке, и в видимой области куска — тот угол, что обведён.
     */
    @Test
    void pieceOfARotatedPageShowsTheFramedCorner() throws IOException {
        Path book = directory.resolve("повёрнутый.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(90);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                text(content, "Inside", 100, 600);
                text(content, "Outside", 400, 200);
            }
            document.save(book.toFile());
        }
        // Показанная страница — 842 × 595: «Inside» видно вверху справа.
        CropFrame frame = new CropFrame(0.65, 0.1, 0.2, 0.2);

        byte[] result = assembly.assemble(List.of(new PdfAssemblyPart.Piece(book, "повёрнутый.pdf", 1, frame)));

        try (PDDocument document = Loader.loadPDF(result)) {
            PDPage page = document.getPage(0);
            assertThat(page.getRotation()).isEqualTo(90);
            assertThat(visibleText(page)).contains("Inside").doesNotContain("Outside");
        }
    }

    @Test
    void vectorAndScanPiecesInOneResult() throws IOException {
        Path book = textBook(3);
        Path scan = scan("скан.pdf");
        CropFrame frame = new CropFrame(0.1, 0.4, 0.8, 0.2);

        byte[] result = assembly.assemble(List.of(
                new PdfAssemblyPart.Piece(book, "сборник.pdf", 2, frame),
                new PdfAssemblyPart.Piece(scan, "скан.pdf", 1, frame)));

        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(visibleText(document.getPage(0))).contains("Problem 2");
            assertThat(visibleText(document.getPage(1))).isBlank();
        }
        // Векторный кусок несёт картинку своей страницы, скан — свой растр.
        assertThat(pageImageWidths(result)).hasSize(2).startsWith(width(2));
    }

    // --- problem-pdf-crop 3.1 Показ страницы --------------------------------

    /** A4 при 150 dpi — 1240 × 1754: выше предела, длинная сторона — 1600. */
    @Test
    void previewOfAnA4PageIsLimitedByItsLongSide() throws IOException {
        Path book = textBook(3);

        BufferedImage shown = shown(assembly.preview(book, "сборник.pdf", AssemblyDraft.Kind.PDF, 2, NORMAL));

        assertThat(shown.getHeight()).isEqualTo(1600);
        assertThat(shown.getWidth()).isEqualTo((int) (PDRectangle.A4.getWidth() * 1600 / PDRectangle.A4.getHeight()));
    }

    /** Маленькая страница не растягивается до 1600: предел — 150 dpi. */
    @Test
    void previewOfASmallPageIsLimitedByResolution() throws IOException {
        Path book = directory.resolve("маленькая.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(144, 72)));
            document.save(book.toFile());
        }

        BufferedImage shown = shown(assembly.preview(book, "маленькая.pdf", AssemblyDraft.Kind.PDF, 1, NORMAL));

        assertThat(shown.getWidth()).isEqualTo(300);
        assertThat(shown.getHeight()).isEqualTo(150);
    }

    /** Повёрнутая страница показывается повёрнутой: рамка считается от этого вида. */
    @Test
    void previewOfARotatedPageIsTurned() throws IOException {
        Path book = directory.resolve("повёрнутый.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(90);
            document.addPage(page);
            document.save(book.toFile());
        }

        BufferedImage shown = shown(assembly.preview(book, "повёрнутый.pdf", AssemblyDraft.Kind.PDF, 1, NORMAL));

        assertThat(shown.getWidth()).isEqualTo(1600);
        assertThat(shown.getHeight()).isLessThan(shown.getWidth());
    }

    @Test
    void previewOfAPageBeyondTheBookIsRefused() throws IOException {
        Path book = textBook(3);

        assertThatThrownBy(() -> assembly.preview(book, "сборник.pdf", AssemblyDraft.Kind.PDF, 4, NORMAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewOfALargePictureIsReducedTo1600() throws IOException {
        Path picture = png("широкая.png", 4000, 1000);

        BufferedImage shown = shown(assembly.preview(picture, "широкая.png", AssemblyDraft.Kind.IMAGE, 1, NORMAL));

        assertThat(shown.getWidth()).isEqualTo(1600);
        assertThat(shown.getHeight()).isEqualTo(400);
    }

    @Test
    void previewOfASmallPictureIsNotEnlarged() throws IOException {
        Path picture = png("картинка.png", 300, 200);

        BufferedImage shown = shown(assembly.preview(picture, "картинка.png", AssemblyDraft.Kind.IMAGE, 1, NORMAL));

        assertThat(shown.getWidth()).isEqualTo(300);
        assertThat(shown.getHeight()).isEqualTo(200);
    }

    // --- crop-frame-zoom 1.3 Крупный показ ----------------------------------

    /** A4 при 300 dpi — 2480 × 3508: выше предела, длинная сторона — 3200. */
    @Test
    void largePreviewOfAnA4PageIsLimitedByItsLongSide() throws IOException {
        Path book = textBook(3);

        BufferedImage shown = shown(assembly.preview(book, "сборник.pdf", AssemblyDraft.Kind.PDF, 2, LARGE));

        assertThat(shown.getHeight()).isEqualTo(3200);
        assertThat(shown.getWidth()).isEqualTo((int) (PDRectangle.A4.getWidth() * 3200 / PDRectangle.A4.getHeight()));
    }

    /** Маленькая страница в крупном показе — не больше 300 dpi. */
    @Test
    void largePreviewOfASmallPageIsLimitedByResolution() throws IOException {
        Path book = directory.resolve("маленькая.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(144, 72)));
            document.save(book.toFile());
        }

        BufferedImage shown = shown(assembly.preview(book, "маленькая.pdf", AssemblyDraft.Kind.PDF, 1, LARGE));

        assertThat(shown.getWidth()).isEqualTo(600);
        assertThat(shown.getHeight()).isEqualTo(300);
    }

    @Test
    void largePreviewOfALargePictureIsReducedTo3200() throws IOException {
        Path picture = png("широкая.png", 4000, 1000);

        BufferedImage shown = shown(assembly.preview(picture, "широкая.png", AssemblyDraft.Kind.IMAGE, 1, LARGE));

        assertThat(shown.getWidth()).isEqualTo(3200);
        assertThat(shown.getHeight()).isEqualTo(800);
    }

    @Test
    void largePreviewOfASmallPictureIsNotEnlarged() throws IOException {
        Path picture = png("картинка.png", 300, 200);

        BufferedImage shown = shown(assembly.preview(picture, "картинка.png", AssemblyDraft.Kind.IMAGE, 1, LARGE));

        assertThat(shown.getWidth()).isEqualTo(300);
        assertThat(shown.getHeight()).isEqualTo(200);
    }

    /** Снимок с ориентацией 6 показывается стоя — как его режет сборка. */
    @Test
    void previewOfAPhotoIsTurnedAsItIsSeen() throws IOException {
        Path photo = directory.resolve("повёрнутый.jpg");
        try (InputStream in = PdfAssemblyTest.class.getResourceAsStream("/ru/locus/file/rotated.jpg")) {
            Files.write(photo, in.readAllBytes());
        }

        BufferedImage shown = shown(assembly.preview(photo, "повёрнутый.jpg", AssemblyDraft.Kind.IMAGE, 1, NORMAL));

        assertThat(shown.getWidth()).isEqualTo(400);
        assertThat(shown.getHeight()).isEqualTo(600);
    }

    @Test
    void pictureHasOnlyOnePageToPreview() throws IOException {
        Path picture = png("картинка.png", 300, 200);

        assertThatThrownBy(() -> assembly.preview(picture, "картинка.png", AssemblyDraft.Kind.IMAGE, 2, NORMAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BufferedImage shown(byte[] jpeg) throws IOException {
        assertThat(PdfAssembly.isJpeg(jpeg)).isTrue();
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    // --- Сборники и разбор результата ---------------------------------------

    /** Ширина картинки страницы {@code number}: по ней узнаётся, какая страница взята. */
    private static int width(int number) {
        return 40 + number;
    }

    /**
     * Сборник, где все страницы делят один словарь ресурсов, а каждая рисует
     * свою картинку из шума — шум не сжимается, и объём картинок честный.
     */
    private Path sharedResourcesBook(int pages) throws IOException {
        Path book = directory.resolve("сборник-" + pages + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDResources shared = new PDResources();
            for (int number = 1; number <= pages; number++) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setResources(shared);
                document.addPage(page);
                drawImage(document, page, noise(width(number), 60, number));
            }
            document.save(book.toFile());
        }
        return book;
    }

    /**
     * Сборник с текстом: заголовок вверху страницы, задача посредине,
     * картинка внизу; ресурсы общие на все страницы.
     */
    private Path textBook(int pages) throws IOException {
        Path book = directory.resolve("текст-" + pages + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDResources shared = new PDResources();
            for (int number = 1; number <= pages; number++) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setResources(shared);
                document.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(document, noise(width(number), 60, number));
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    text(content, "Header " + number, 72, 800);
                    text(content, "Problem " + number, 72, 421);
                    content.drawImage(image, 72, 72);
                }
            }
            document.save(book.toFile());
        }
        return book;
    }

    private static void text(PDPageContentStream content, String text, float x, float y) throws IOException {
        content.beginText();
        content.setFont(HELVETICA, 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    /** Скан: страница A4, которая только рисует одну картинку во весь лист. */
    private Path scan(String name) throws IOException {
        Path file = directory.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject image = LosslessFactory.createFromImage(document, noise(248, 351, 5));
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            document.save(file.toFile());
        }
        return file;
    }

    /**
     * Текст, начало которого лежит в видимой области страницы: содержимое
     * за рамкой остаётся в потоке (ADR-0045), поэтому «в куске» — значит
     * «видно», а не «записано».
     */
    private static String visibleText(PDPage page) throws IOException {
        PDRectangle visible = page.getCropBox();
        StringBuilder text = new StringBuilder();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition position) {
                // Начало глифа PDFBox отдаёт от левого нижнего угла видимой
                // области, без поворота страницы.
                Matrix matrix = position.getTextMatrix();
                float x = matrix.getTranslateX();
                float y = matrix.getTranslateY();
                if (x >= 0 && y >= 0 && x <= visible.getWidth() && y <= visible.getHeight()) {
                    text.append(position.getUnicode());
                }
            }
        };
        try (PDDocument single = new PDDocument()) {
            single.addPage(page);
            stripper.getText(single);
        }
        return text.toString();
    }

    private static void drawImage(PDDocument document, PDPage page, BufferedImage picture) throws IOException {
        PDImageXObject image = LosslessFactory.createFromImage(document, picture);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(image, 72, 72);
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /**
     * Перенумеровывает каналы JPEG с 1, 2, 3 на 0, 1, 2 — в заголовке кадра
     * (SOF) и в заголовках проходов (SOS), — как пишет камера Android-планшета.
     */
    private static byte[] channelsNumberedFromZero(byte[] jpeg) {
        byte[] result = jpeg.clone();
        int at = 2;
        while (at + 4 <= result.length && (result[at] & 0xFF) == 0xFF) {
            int marker = result[at + 1] & 0xFF;
            int length = ((result[at + 2] & 0xFF) << 8) | (result[at + 3] & 0xFF);
            if (marker == 0xC0 || marker == 0xC2) {
                int channels = result[at + 9] & 0xFF;
                for (int i = 0; i < channels; i++) {
                    result[at + 10 + 3 * i]--;
                }
            } else if (marker == 0xDA) {
                int channels = result[at + 4] & 0xFF;
                for (int i = 0; i < channels; i++) {
                    result[at + 5 + 2 * i]--;
                }
                return result;
            }
            at += 2 + length;
        }
        throw new IllegalStateException("В JPEG не найден заголовок прохода");
    }

    private static BufferedImage noise(int width, int height, long seed) {
        Random random = new Random(seed);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }

    private Path png(String name, int width, int height) throws IOException {
        Path file = directory.resolve(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(noise(width, height, width * 31L + height), "png", out);
        return Files.write(file, out.toByteArray());
    }

    private static float[] size(PDPage page) {
        return new float[] {page.getMediaBox().getWidth(), page.getMediaBox().getHeight()};
    }

    /** Ширины всех картинок, записанных в файл, — сколько бы их ни было достижимо. */
    private static List<Integer> imageWidths(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return images(document).stream().map(image -> image.getInt(COSName.WIDTH)).toList();
        }
    }

    /** Ширины картинок, нарисованных на страницах, по порядку страниц. */
    private static List<Integer> pageImageWidths(byte[] pdf) throws IOException {
        List<Integer> widths = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDPage page : document.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                        widths.add(image.getWidth());
                    }
                }
            }
        }
        return widths;
    }

    private static List<COSStream> images(PDDocument document) throws IOException {
        List<COSStream> images = new ArrayList<>();
        for (COSBase object : objects(document)) {
            if (object instanceof COSStream stream && COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
                images.add(stream);
            }
        }
        return images;
    }

    private static List<String> baseFonts(byte[] pdf) throws IOException {
        List<String> fonts = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (COSBase object : objects(document)) {
                if (object instanceof COSDictionary dictionary
                        && COSName.FONT.equals(dictionary.getCOSName(COSName.TYPE))) {
                    fonts.add(dictionary.getNameAsString(COSName.BASE_FONT));
                }
            }
        }
        return fonts;
    }

    /** Все объекты файла по таблице ссылок — то, что записано, а не то, что достижимо со страниц. */
    private static List<COSBase> objects(PDDocument document) throws IOException {
        List<COSBase> objects = new ArrayList<>();
        for (COSObjectKey key : document.getDocument().getXrefTable().keySet()) {
            COSObject object = document.getDocument().getObjectFromPool(key);
            COSBase value = object.getObject();
            if (value != null && !(value instanceof COSArray)) {
                objects.add(value);
            }
        }
        return objects;
    }
}
