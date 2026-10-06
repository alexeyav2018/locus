package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
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
                .hasMessageContaining("заметки.txt");
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

    private static void drawImage(PDDocument document, PDPage page, BufferedImage picture) throws IOException {
        PDImageXObject image = LosslessFactory.createFromImage(document, picture);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(image, 72, 72);
        }
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
