package ru.locus.problem;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.scilab.forge.jlatexmath.JMathTeXException;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

/**
 * Отрисовка Текста Задачи в PDF — чистая функция «Markdown с формулами
 * LaTeX → PDF» внутри приложения (ADR-0055, ADR-0058).
 *
 * <p>Страницу вёрстает openhtmltopdf поверх того же PDFBox, что у сборки:
 * абзацы, списки и переносы делает движок CSS, а не этот класс. Формулу
 * рисует JLaTeXMath через Graphics2D в отдельный маленький PDF — вектором,
 * а не картинкой, глифы контурами, — и движок вставляет этот PDF в строку
 * как встроенный документ {@code data:application/pdf}. Не элементом
 * {@code <object>} с собственной отрисовкой: такой элемент openhtmltopdf
 * в строке рисует не на своём месте, а встроенный PDF ставит по границам
 * его блока, как картинку.
 *
 * <p><b>Формулы вынимаются до Markdown.</b> Обратная косая в Markdown —
 * экранирование: прошедшая через CommonMark формула {@code \{x\}} стала бы
 * {@code {x}}, а {@code \\} — одной косой, и PDF разошёлся бы с текстом
 * молча. Поэтому сначала текст проходит сканер: {@code $$…$$} — выключная
 * формула, {@code $…$} — строчная, {@code \$} — знак доллара; формула
 * заменяется меткой, которую Markdown не трогает, а после перевода
 * в разметку метка становится элементом формулы. Это разбор записи,
 * а не содержимого: что написано в задаче, Локус не понимает (ADR-0055, п. 4).
 *
 * <p><b>Наружу отрисовка не ходит.</b> Текст пишет человек или модель,
 * и картинка или таблица стилей по адресу из него превратила бы сохранение
 * в запрос сервера куда угодно. Картинки Markdown выводятся подписью, сырой
 * HTML — текстом, а движку запрещена загрузка любых внешних ресурсов;
 * шрифт берётся из ресурсов приложения.
 *
 * <p>Одиночный перевод строки — перевод строки, как в Obsidian, где учитель
 * правит текст: цепочка преобразований пишется строка под строкой,
 * и склеивать её в абзац было бы неожиданно.
 */
public final class ProblemTextRendering {

    /** Кегль текста в пикселях CSS: 12 pt. Формулы рисуются тем же кеглем. */
    private static final float TEXT_SIZE_PX = 16f;

    private static final String FONT_FAMILY = "PT Serif";
    private static final String FONT_DIRECTORY = "/fonts/pt-serif/";
    private static final String STYLESHEET = "problem-text.css";

    /** Метка формулы — символы личного пользования Юникода: Markdown их не трогает. */
    private static final char MARK_OPEN = '\uE000';
    private static final char MARK_CLOSE = '\uE001';
    private static final Pattern MARK = Pattern.compile(MARK_OPEN + "(\\d+)" + MARK_CLOSE);

    /** Перевод строки вплотную к выключной формуле дал бы пустую строку над ней или под ней. */
    private static final String BREAK = "<br />";

    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak(BREAK + "\n")
            .nodeRendererFactory(context -> new ImageAsCaption(context.getWriter()))
            .build();

    /*
     * Движок пишет в stderr своим журналом по четыре строки INFO на каждую
     * отрисовку; предупреждения о настоящих неполадках оставлены.
     */
    static {
        for (String logger : XRLog.listRegisteredLoggers()) {
            XRLog.setLevel(logger, Level.WARNING);
        }
    }

    private ProblemTextRendering() {
    }

    /**
     * Отрисовывает текст в PDF.
     *
     * @throws IllegalArgumentException формула не разбирается JLaTeXMath
     *         или знак {@code $} открывает формулу и не закрывает её; текст
     *         отказа называет формулу или строку
     */
    public static byte[] render(String markdown) {
        List<Formula> formulas = new ArrayList<>();
        String marked = markFormulas(markdown, formulas);
        String body = withFormulas(HTML.render(MARKDOWN.parse(marked)), formulas);
        return pdf(document(body));
    }

    /**
     * Сканер формул. Обратная косая вне формулы и внутри неё забирает
     * следующий знак целиком — так {@code \$} остаётся знаком доллара
     * и для Markdown, и для LaTeX. Код в обратных кавычках пропускается:
     * доллар в нём — не формула.
     */
    private static String markFormulas(String text, List<Formula> formulas) {
        StringBuilder marked = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            char current = text.charAt(at);
            if (current == '\\' && at + 1 < text.length()) {
                marked.append(current).append(text.charAt(at + 1));
                at += 2;
            } else if (current == '`') {
                int end = codeSpanEnd(text, at);
                marked.append(text, at, end);
                at = end;
            } else if (current == '$') {
                boolean display = at + 1 < text.length() && text.charAt(at + 1) == '$';
                int start = at + (display ? 2 : 1);
                int close = closingDollar(text, start, display);
                if (close < 0) {
                    throw new IllegalArgumentException("Строка " + lineOf(text, at)
                            + ": знак $ открывает формулу и не закрывает её; знак доллара в тексте пишется \\$");
                }
                formulas.add(Formula.of(text.substring(start, close), display));
                marked.append(MARK_OPEN).append(formulas.size() - 1).append(MARK_CLOSE);
                at = close + (display ? 2 : 1);
            } else {
                marked.append(current);
                at++;
            }
        }
        return marked.toString();
    }

    /** Конец кода в обратных кавычках: за такой же серией кавычек; не закрыт — серия просто текст. */
    private static int codeSpanEnd(String text, int start) {
        int run = start;
        while (run < text.length() && text.charAt(run) == '`') {
            run++;
        }
        String fence = text.substring(start, run);
        int close = text.indexOf(fence, run);
        return close < 0 ? run : close + fence.length();
    }

    private static int closingDollar(String text, int from, boolean display) {
        int at = from;
        while (at < text.length()) {
            char current = text.charAt(at);
            if (current == '\\') {
                at += 2;
            } else if (current == '$') {
                if (!display) {
                    return at;
                }
                if (at + 1 < text.length() && text.charAt(at + 1) == '$') {
                    return at;
                }
                at++;
            } else {
                at++;
            }
        }
        return -1;
    }

    private static int lineOf(String text, int position) {
        int line = 1;
        for (int at = 0; at < position; at++) {
            if (text.charAt(at) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Метки формул — элементами формулы с размерами её рисунка. */
    private static String withFormulas(String html, List<Formula> formulas) {
        String tidy = html;
        for (int index = 0; index < formulas.size(); index++) {
            if (formulas.get(index).display()) {
                String mark = MARK_OPEN + String.valueOf(index) + MARK_CLOSE;
                tidy = tidy.replace(BREAK + "\n" + mark, mark).replace(mark + BREAK + "\n", mark);
            }
        }
        Matcher marks = MARK.matcher(tidy);
        StringBuilder result = new StringBuilder(tidy.length());
        while (marks.find()) {
            int index = Integer.parseInt(marks.group(1));
            marks.appendReplacement(result, Matcher.quoteReplacement(formulas.get(index).element()));
        }
        marks.appendTail(result);
        return result.toString();
    }

    private static String document(String body) {
        return "<html><head><style>" + stylesheet() + "</style></head><body>" + body + "</body></html>";
    }

    private static byte[] pdf(String document) {
        PdfRendererBuilder builder = new PdfRendererBuilder()
                .useFastMode()
                .withHtmlContent(document, null)
                .useExternalResourceAccessControl((uri, type) -> false,
                        ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI)
                .useExternalResourceAccessControl((uri, type) -> false,
                        ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
        font(builder, "PT_Serif-Web-Regular.ttf", 400, FontStyle.NORMAL);
        font(builder, "PT_Serif-Web-Bold.ttf", 700, FontStyle.NORMAL);
        font(builder, "PT_Serif-Web-Italic.ttf", 400, FontStyle.ITALIC);
        font(builder, "PT_Serif-Web-BoldItalic.ttf", 700, FontStyle.ITALIC);

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try {
            builder.toStream(pdf).run();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return pdf.toByteArray();
    }

    private static void font(PdfRendererBuilder builder, String file, int weight, FontStyle style) {
        builder.useFont(() -> resource(FONT_DIRECTORY + file), FONT_FAMILY, weight, style, true);
    }

    private static String stylesheet() {
        try (InputStream css = ProblemTextRendering.class.getResourceAsStream(STYLESHEET)) {
            return new String(css.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static InputStream resource(String path) {
        InputStream stream = ProblemTextRendering.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Нет ресурса " + path);
        }
        return stream;
    }

    /**
     * Формула и её рисунок. Рисунок строится сразу при сканировании:
     * неразбираемая формула отказывает до вёрстки, называя себя.
     */
    private record Formula(String latex, boolean display, TeXIcon icon) {

        static Formula of(String latex, boolean display) {
            try {
                TeXIcon icon = new TeXFormula(latex).createTeXIcon(
                        display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT, TEXT_SIZE_PX);
                return new Formula(latex, display, icon);
            } catch (JMathTeXException failure) {
                throw new IllegalArgumentException("Формула " + (display ? "$$" : "$") + latex
                        + (display ? "$$" : "$") + " не отрисовывается: " + failure.getMessage(), failure);
            }
        }

        /**
         * Элемент формулы. Нижний край замещаемого элемента стоит на базовой
         * линии строки, поэтому строчная формула опускается на свою глубину —
         * и её собственная базовая линия совпадает с базовой линией текста.
         * Размеры заданы явно в пикселях CSS: страница формулы меряется
         * в пунктах, по одному на пиксель рисунка, и движок её сжимает.
         */
        String element() {
            return "<img alt=\"\" src=\"data:application/pdf;base64,"
                    + Base64.getEncoder().encodeToString(pdf()) + "\""
                    + (display ? " class=\"display\"" : "")
                    + " style=\"width: " + icon.getIconWidth() + "px; height: " + icon.getIconHeight() + "px;"
                    + (display ? "" : " vertical-align: -" + icon.getIconDepth() + "px;")
                    + "\" />";
        }

        /** Одностраничный PDF с формулой во всю страницу. */
        private byte[] pdf() {
            float width = Math.max(icon.getIconWidth(), 1);
            float height = Math.max(icon.getIconHeight(), 1);
            try (PDDocument document = new PDDocument()) {
                PdfBoxGraphics2D graphics = new PdfBoxGraphics2D(document, width, height);
                icon.paintIcon(null, graphics, 0, 0);
                graphics.dispose();
                PDPage page = new PDPage(new PDRectangle(width, height));
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawForm(graphics.getXFormObject());
                }
                ByteArrayOutputStream pdf = new ByteArrayOutputStream();
                document.save(pdf);
                return pdf.toByteArray();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    /** Картинка Markdown — своей подписью: загружать её отрисовка не станет. */
    private record ImageAsCaption(HtmlWriter writer) implements NodeRenderer {

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class);
        }

        @Override
        public void render(Node image) {
            for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Text text) {
                    writer.text(text.getLiteral());
                }
            }
        }
    }
}
