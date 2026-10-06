package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Рамка (ADR-0045): границы, поле формы и перевод в координаты PDF.
 *
 * Перевод проверяется не выкладками, а отрисовщиком PDFBox — тем же, что
 * показывает страницу Администратору: прямоугольник рамки закрашивается
 * на повёрнутой странице с непустой видимой областью, страница рисуется,
 * и закрашенное должно оказаться ровно там, где Администратор обвёл.
 */
class CropFrameTest {

    @Test
    void frameMustStayWithinThePage() {
        assertThatThrownBy(() -> new CropFrame(0.5, 0, 0.6, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("за пределы страницы");
        assertThatThrownBy(() -> new CropFrame(-0.1, 0, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CropFrame(0, 0, 0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("нулевой");
        assertThatThrownBy(() -> new CropFrame(0, 0, Double.NaN, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundingAtTheEdgeIsTolerated() {
        CropFrame frame = new CropFrame(0.5, 0.5, 0.50005, 0.5);
        assertThat(frame.left() + frame.width()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void formFieldRoundTrips() {
        CropFrame frame = new CropFrame(0.1, 0.25, 0.5, 0.125);
        assertThat(frame.formValue()).isEqualTo("0.1000;0.2500;0.5000;0.1250");
        assertThat(CropFrame.parse(frame.formValue())).isEqualTo(frame);
    }

    @Test
    void emptyFieldMeansWholePage() {
        assertThat(CropFrame.parse("")).isNull();
        assertThat(CropFrame.parse(null)).isNull();
        assertThat(CropFrame.parse("  ")).isNull();
    }

    @Test
    void brokenFieldIsRefused() {
        assertThatThrownBy(() -> CropFrame.parse("0.1;0.2;0.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не разбирается");
        assertThatThrownBy(() -> CropFrame.parse("a;b;c;d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не разбирается");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void frameLandsWhereTheAdministratorDrewIt(int rotation) throws IOException {
        CropFrame frame = new CropFrame(0.1, 0.2, 0.3, 0.4);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 200));
            // Видимая область не с нуля — чтобы сдвиг начала тоже проверялся.
            PDRectangle visible = new PDRectangle(50, 20, 300, 150);
            page.setCropBox(visible);
            page.setRotation(rotation);
            document.addPage(page);
            PDRectangle box = frame.on(visible, rotation);
            try (PDPageContentStream drawing = new PDPageContentStream(document, page)) {
                drawing.setNonStrokingColor(Color.BLACK);
                drawing.addRect(box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
                drawing.fill();
            }

            BufferedImage shown = new PDFRenderer(document).renderImage(0, 2f, ImageType.RGB);
            int[] dark = darkBounds(shown);
            double w = shown.getWidth();
            double h = shown.getHeight();
            assertThat(dark[0] / w).isCloseTo(frame.left(), within(0.02));
            assertThat(dark[1] / h).isCloseTo(frame.top(), within(0.02));
            assertThat((dark[2] + 1) / w).isCloseTo(frame.left() + frame.width(), within(0.02));
            assertThat((dark[3] + 1) / h).isCloseTo(frame.top() + frame.height(), within(0.02));
        }
    }

    @Test
    void rotationNotMultipleOfNinetyIsRefused() {
        CropFrame frame = new CropFrame(0, 0, 1, 1);
        assertThatThrownBy(() -> frame.on(PDRectangle.A4, 45))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Наименьший прямоугольник тёмных пикселей: {@code minX, minY, maxX, maxY}. */
    private static int[] darkBounds(BufferedImage image) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF) < 128) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertThat(maxX).as("на отрисованной странице есть закрашенное").isNotNegative();
        return new int[] {minX, minY, maxX, maxY};
    }
}
