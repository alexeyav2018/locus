package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sun.net.httpserver.HttpServer;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;

/**
 * Отрисовка Текста Задачи (ADR-0058). Первые три теста — проверка самой
 * связки openhtmltopdf и JLaTeXMath: формула ложится вектором, строчная
 * стоит на базовой линии текста, кириллица видна и в тексте, и в формуле.
 */
class ProblemTextRenderingTest {

    /** Пиксель CSS в пунктах PDF. */
    private static final float PT_PER_PX = 72f / 96f;

    @Test
    void formulasAreVectorNotPictures() throws IOException {
        byte[] pdf = ProblemTextRendering.render("""
                Решите уравнение $\\log_2 (x+1) = 3$.

                $$\\frac{1}{2} + \\sqrt{x} = 1$$
                """);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDResources resources = document.getPage(0).getResources();
            assertThat(imagesIn(resources)).as("картинок в отрисовке нет").isZero();
            assertThat(formsIn(resources)).as("формулы — формы с контурами").isGreaterThanOrEqualTo(2);
            assertThat(new PDFTextStripper().getText(document)).contains("Решите уравнение");
        }
    }

    @Test
    void inlineFormulaSitsOnTheBaselineOfText() throws IOException {
        byte[] pdf = ProblemTextRendering.render("ааааа $x_1$ ааааа");
        float depthPt = new TeXFormula("x_1").createTeXIcon(TeXConstants.STYLE_TEXT, 16f).getIconDepth() * PT_PER_PX;

        try (PDDocument document = Loader.loadPDF(pdf)) {
            float textBaseline = baselineOfText(document);
            List<Float> bottoms = formBottoms(document.getPage(0));

            assertThat(bottoms).hasSize(1);
            assertThat(bottoms.get(0) + depthPt)
                    .as("базовая линия формулы — базовая линия текста")
                    .isCloseTo(textBaseline, within(1.2f));
        }
    }

    @Test
    void cyrillicIsVisibleInsideFormula() throws IOException {
        assertThat(darkPixels(ProblemTextRendering.render("$\\text{км}$")))
                .as("кириллица в \\text{} нарисована")
                .isGreaterThan(darkPixels(ProblemTextRendering.render("$\\text{}$")) + 20);
    }

    @Test
    void paragraphsListsAndFormulasRender() throws IOException {
        byte[] pdf = ProblemTextRendering.render("""
                Найдите корни уравнения.

                1. Область допустимых значений: $x > -1$.
                2. Переходим к равносильному:
                $$x + 1 = 2^3$$
                3. Ответ: $x = 7$.
                """);

        assertThat(text(pdf)).contains("Найдите корни уравнения", "Область допустимых значений", "Ответ");
    }

    @Test
    void examFormulasRender() {
        List<String> formulas = List.of(
                "\\frac{a}{b}", "\\sqrt[3]{x^2}", "\\log_2 x", "\\lg 100", "x^{2n+1}", "a_{n-1}",
                "\\begin{cases} x > 0 \\\\ x \\ne 1 \\end{cases}",
                "\\begin{array}{l} 2x = 4 \\\\ x = 2 \\end{array}",
                "\\left( \\frac{1}{3} \\right)^{x} \\geq 9", "\\sin^2 \\alpha + \\cos^2 \\alpha = 1",
                "\\pi", "\\infty", "\\in", "\\cup", "\\Rightarrow", "\\Leftrightarrow");

        for (String formula : formulas) {
            assertThat(ProblemTextRendering.render("$$" + formula + "$$")).as(formula).isNotEmpty();
        }
    }

    @Test
    void unknownCommandIsRefusedNamingTheFormula() {
        assertThatThrownBy(() -> ProblemTextRendering.render("Решение: $\\foo{x}$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\\foo{x}");
    }

    @Test
    void unclosedDollarIsRefusedNamingTheLine() {
        assertThatThrownBy(() -> ProblemTextRendering.render("Первая строка\nВторая $x+1\nТретья"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Строка 2");
    }

    @Test
    void escapedDollarIsADollarSign() throws IOException {
        assertThat(text(ProblemTextRendering.render("цена 5 \\$ и 7 \\$"))).contains("цена 5 $ и 7 $");
    }

    @Test
    void backslashesInsideFormulaReachLatexIntact() throws IOException {
        // Прошедшая через Markdown формула \{x\} стала бы {x} — группой без скобок
        assertThat(darkPixels(ProblemTextRendering.render("$\\{x\\}$")))
                .as("фигурные скобки нарисованы")
                .isGreaterThan(darkPixels(ProblemTextRendering.render("${x}$")) + 20);
    }

    @Test
    void imageIsNeverFetchedAndShowsItsCaption() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] pdf = ProblemTextRendering.render("![чертёж](" + address + "/a.png)\n\n[ссылка](" + address + "/b)");

            assertThat(text(pdf)).contains("чертёж");
            assertThat(requests).as("отрисовка не обращалась по адресу из текста").hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rawHtmlIsShownAsText() throws IOException {
        assertThat(text(ProblemTextRendering.render("<b>жирно</b>"))).contains("<b>жирно</b>");
    }

    @Test
    void singleLineBreakIsKept() throws IOException {
        String text = text(ProblemTextRendering.render("первая строка\nвторая строка"));
        assertThat(text).containsPattern("первая строка\\R+вторая строка");
    }

    @Test
    void solutionOfAPageRendersInReasonableTime() {
        StringBuilder solution = new StringBuilder();
        while (solution.length() < 3_000) {
            solution.append("Заменим $t = 2^x$, $t > 0$; получим $t^2 - 5t + 4 = 0$, откуда\n")
                    .append("$$t_{1,2} = \\frac{5 \\pm \\sqrt{25 - 16}}{2}$$\n\n");
        }
        long started = System.nanoTime();

        assertThat(ProblemTextRendering.render(solution.toString())).isNotEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int imagesIn(PDResources resources) throws IOException {
        int images = 0;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject) {
                images++;
            } else if (object instanceof PDFormXObject form && form.getResources() != null) {
                images += imagesIn(form.getResources());
            }
        }
        return images;
    }

    private static int formsIn(PDResources resources) throws IOException {
        int forms = 0;
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDFormXObject) {
                forms++;
            }
        }
        return forms;
    }

    private static float baselineOfText(PDDocument document) throws IOException {
        List<Float> baselines = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                for (TextPosition position : positions) {
                    if (position.getUnicode().equals("а")) {
                        baselines.add(position.getTextMatrix().getTranslateY());
                    }
                }
            }
        };
        stripper.getText(document);
        assertThat(baselines).isNotEmpty();
        return baselines.get(0);
    }

    /** Нижние края размещённых форм в координатах страницы. */
    private static List<Float> formBottoms(PDPage page) throws IOException {
        List<Float> bottoms = new ArrayList<>();
        PDFStreamEngine engine = new PDFStreamEngine() {
            {
                addOperator(new Save(this));
                addOperator(new Restore(this));
                addOperator(new Concatenate(this));
                addOperator(new DrawObject(this));
            }

            @Override
            public void showForm(PDFormXObject form) {
                Matrix placement = form.getMatrix().multiply(getGraphicsState().getCurrentTransformationMatrix());
                Point2D.Float corner = placement.transformPoint(form.getBBox().getLowerLeftX(),
                        form.getBBox().getLowerLeftY());
                bottoms.add(corner.y);
            }
        };
        engine.processPage(page);
        return bottoms;
    }

    private static int darkPixels(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 100);
            int dark = 0;
            for (int y = 0; y < page.getHeight(); y++) {
                for (int x = 0; x < page.getWidth(); x++) {
                    if ((page.getRGB(x, y) & 0xFF) < 128) {
                        dark++;
                    }
                }
            }
            return dark;
        }
    }
}
