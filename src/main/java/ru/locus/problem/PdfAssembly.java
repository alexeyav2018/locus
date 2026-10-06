package ru.locus.problem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.util.exif.ExifUtils;
import net.coobird.thumbnailator.util.exif.Orientation;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

/**
 * Сборка PDF Задачи из картинок и целых страниц PDF (ADR-0044).
 *
 * <p>Чистая функция над файлами: ни базы, ни хранилища, ни прав. Поэтому
 * самое хрупкое — что попадает в результат со страниц сборника — проверяется
 * модульными тестами без контекста Spring.
 *
 * <p>Содержимое источников <b>не интерпретируется</b>: страница переносится
 * целиком, с тем, что нарисовано в её потоке содержимого, а поток читается
 * только затем, чтобы узнать, какие ресурсы он называет по имени. Текста
 * и формул система по-прежнему не знает (ADR-0008).
 *
 * <p>Обычный перенос страницы ({@code importPage}, {@code Splitter}) здесь
 * не годится: он копирует словарь страницы как есть, а при записи PDFBox
 * сохраняет всё, что из него достижимо. Страницы сборника часто делят один
 * словарь ресурсов со всеми картинками и шрифтами книги, а пометки
 * ссылаются на соседние страницы — и три страницы результата весили бы
 * как весь сборник (design.md, «Страница переносится белым списком ключей
 * и с вычищенными ресурсами»).
 */
@Component
public class PdfAssembly {

    /** Пункт PDF на пиксель картинки: страница размером с картинку (design.md). */
    private static final float POINTS_PER_PIXEL = 1f;

    private static final COSName PATTERN_TYPE = COSName.getPDFName("PatternType");

    /**
     * Собирает PDF из кусков в их порядке.
     *
     * @throws IllegalArgumentException если кусков нет, диапазон выходит
     *                                  за число страниц, PDF не разбирается
     *                                  или закрыт паролем, картинка не JPEG
     *                                  и не PNG
     */
    public byte[] assemble(List<PdfAssemblyPart> parts) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Не указано ни одного источника: собирать нечего");
        }
        Map<Path, PDDocument> sources = new LinkedHashMap<>();
        try (PDDocument result = new PDDocument()) {
            Pruning pruning = new Pruning();
            for (PdfAssemblyPart part : parts) {
                switch (part) {
                    case PdfAssemblyPart.Image image -> result.addPage(imagePage(result, image));
                    case PdfAssemblyPart.Pages pages -> {
                        PDDocument source = sources.get(pages.file());
                        if (source == null) {
                            source = open(pages.file(), pages.name());
                            sources.put(pages.file(), source);
                        }
                        requireRange(source, pages);
                        for (int number = pages.from(); number <= pages.to(); number++) {
                            result.addPage(transplanted(source.getPage(number - 1), pruning));
                        }
                    }
                }
            }
            // Источники закрываются только после записи: страницы результата
            // ссылаются на их объекты, и читаются те при сохранении.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            result.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось собрать PDF", e);
        } finally {
            for (PDDocument source : sources.values()) {
                closeQuietly(source);
            }
        }
    }

    /**
     * Число страниц PDF — его же показывают Администратору при загрузке,
     * чтобы указать диапазон. Здесь же отказ неразбираемому и закрытому
     * паролем PDF: черновик проверяется при загрузке, а не при сборке.
     *
     * @throws IllegalArgumentException если файл не PDF или закрыт паролем
     */
    public int pageCount(Path file, String name) {
        try (PDDocument document = open(file, name)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PDDocument open(Path file, String name) {
        try {
            return Loader.loadPDF(file.toFile());
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("PDF «" + name + "» закрыт паролем: откройте его и сохраните без пароля");
        } catch (IOException e) {
            throw new IllegalArgumentException("Файл «" + name + "» не разбирается как PDF");
        }
    }

    private static void requireRange(PDDocument source, PdfAssemblyPart.Pages pages) {
        int count = source.getNumberOfPages();
        if (pages.to() > count) {
            throw new IllegalArgumentException("Источник «" + pages.name() + "»: указаны страницы с "
                    + pages.from() + " по " + pages.to() + ", а в нём " + count + " стр.");
        }
    }

    /**
     * Новая страница со страницы источника — белым списком ключей.
     *
     * <p>Берутся только размеры, поворот, масштаб и поток содержимого;
     * геттеры {@link PDPage} учитывают наследование от дерева страниц,
     * поэтому унаследованный размер не теряется. {@code /Annots},
     * {@code /Parent}, {@code /B}, {@code /StructParents} не переносятся:
     * они указывают на соседние страницы и дерево документа, и запись
     * результата утянула бы их за собой (ADR-0044, «Последствия»).
     */
    private static PDPage transplanted(PDPage source, Pruning pruning) throws IOException {
        COSDictionary page = new COSDictionary();
        page.setItem(COSName.TYPE, COSName.PAGE);
        COSBase contents = source.getCOSObject().getItem(COSName.CONTENTS);
        if (contents != null) {
            page.setItem(COSName.CONTENTS, contents);
        }
        PDPage transplanted = new PDPage(page);
        transplanted.setMediaBox(source.getMediaBox());
        transplanted.setCropBox(source.getCropBox());
        transplanted.setRotation(source.getRotation());
        if (source.getUserUnit() != 1f) {
            transplanted.setUserUnit(source.getUserUnit());
        }
        PDResources resources = source.getResources();
        if (resources != null) {
            Set<COSName> used = namesIn(tokens(new PDFStreamParser(source)));
            page.setItem(COSName.RESOURCES, pruning.pruned(resources.getCOSObject(), used));
        }
        return transplanted;
    }

    /**
     * Вычистка словарей ресурсов. Помнит уже вычищенные вложенные формы
     * и узоры: одна форма бывает нарисована на нескольких взятых страницах,
     * а форма, рисующая саму себя, зациклила бы рекурсию.
     */
    private static final class Pruning {

        private final Set<COSStream> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        /**
         * Новый словарь ресурсов, где остались только записи с именами
         * из {@code used}.
         *
         * <p>Исходный словарь <b>не правится</b>: его делят другие страницы
         * сборника, и вычистка под одну страницу отняла бы ресурсы у соседней.
         * Категория записи не учитывается — совпадение имён в разных
         * категориях оставит лишнюю запись, а не потеряет нужную (design.md).
         *
         * <p>Форма или узор без собственного {@code /Resources} рисуют
         * ресурсами того, кто их нарисовал, — их имена добавляются к именам
         * владельца, пока набор растёт.
         */
        COSDictionary pruned(COSDictionary resources, Set<COSName> used) throws IOException {
            Set<COSName> names = new HashSet<>(used);
            boolean grown = true;
            while (grown) {
                grown = false;
                for (COSStream nested : nestedContent(resources, names)) {
                    if (nested.getDictionaryObject(COSName.RESOURCES) == null
                            && names.addAll(namesIn(tokens(nested)))) {
                        grown = true;
                    }
                }
            }

            COSDictionary pruned = new COSDictionary();
            for (COSName category : resources.keySet()) {
                COSBase entries = resources.getDictionaryObject(category);
                if (entries instanceof COSDictionary dictionary) {
                    COSDictionary kept = new COSDictionary();
                    for (COSName name : dictionary.keySet()) {
                        if (names.contains(name)) {
                            kept.setItem(name, dictionary.getItem(name));
                        }
                    }
                    if (kept.size() > 0) {
                        pruned.setItem(category, kept);
                    }
                } else if (entries != null) {
                    // /ProcSet — массив имён, никого за собой не тянет.
                    pruned.setItem(category, resources.getItem(category));
                }
            }

            for (COSStream nested : nestedContent(resources, names)) {
                pruneOwn(nested);
            }
            return pruned;
        }

        /**
         * Собственные ресурсы формы или узора вычищаются по их собственному
         * потоку. Правка на месте здесь допустима: результат зависит только
         * от потока самого объекта, а исходник после сборки выбрасывается.
         */
        private void pruneOwn(COSStream nested) throws IOException {
            if (!visited.add(nested)) {
                return;
            }
            COSBase own = nested.getDictionaryObject(COSName.RESOURCES);
            if (own instanceof COSDictionary resources) {
                nested.setItem(COSName.RESOURCES, pruned(resources, namesIn(tokens(nested))));
            }
        }

        /**
         * Взятые формы ({@code /XObject} с {@code /Subtype /Form}) и мозаичные
         * узоры ({@code /PatternType 1}) — всё, что рисуется своим потоком
         * со своими ресурсами. Шрифты Type3 переносятся целиком: случай
         * редкий, а вычистка {@code /CharProcs} дороже пользы (design.md).
         */
        private static List<COSStream> nestedContent(COSDictionary resources, Set<COSName> names) {
            List<COSStream> nested = new ArrayList<>();
            for (COSName category : List.of(COSName.XOBJECT, COSName.PATTERN)) {
                if (resources.getDictionaryObject(category) instanceof COSDictionary entries) {
                    for (COSName name : entries.keySet()) {
                        if (names.contains(name) && entries.getDictionaryObject(name) instanceof COSStream stream
                                && (COSName.FORM.equals(stream.getCOSName(COSName.SUBTYPE))
                                    || stream.getInt(PATTERN_TYPE) == 1)) {
                            nested.add(stream);
                        }
                    }
                }
            }
            return nested;
        }
    }

    private static List<Object> tokens(PDFStreamParser parser) throws IOException {
        return parser.parse();
    }

    private static List<Object> tokens(COSStream stream) throws IOException {
        try (InputStream in = stream.createInputStream()) {
            return new PDFStreamParser(in.readAllBytes()).parse();
        }
    }

    /**
     * Все имена среди операндов потока — включая параметры встроенных
     * картинок, где цветовое пространство бывает названо именем ресурса.
     */
    private static Set<COSName> namesIn(List<Object> tokens) {
        Set<COSName> names = new HashSet<>();
        for (Object token : tokens) {
            if (token instanceof COSName name) {
                names.add(name);
            } else if (token instanceof Operator operator && operator.getImageParameters() != null) {
                for (COSBase value : operator.getImageParameters().getValues()) {
                    if (value instanceof COSName name) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /**
     * Картинка — одна страница своего размера.
     *
     * <p>JPEG вкладывается байт в байт, без перекодирования; PNG — без
     * потерь. Пережатия до предела сканов Работ здесь нет: это условие
     * задачи, а не снимок решения (карточка элемента).
     *
     * <p>Ориентация по сведениям о съёмке соблюдается матрицей размещения:
     * перекодировать JPEG ради поворота значило бы потерять в качестве.
     * У PNG сведений о съёмке обычно нет, и они не читаются.
     */
    private static PDPage imagePage(PDDocument result, PdfAssemblyPart.Image part) throws IOException {
        byte[] content = Files.readAllBytes(part.file());
        PDImageXObject image;
        Orientation orientation = Orientation.TOP_LEFT;
        if (isJpeg(content)) {
            image = JPEGFactory.createFromByteArray(result, content);
            orientation = orientationOf(content, part.name());
        } else if (isPng(content)) {
            var decoded = ImageIO.read(new ByteArrayInputStream(content));
            if (decoded == null) {
                throw new IllegalArgumentException("Картинка «" + part.name() + "» не разбирается");
            }
            image = LosslessFactory.createFromImage(result, decoded);
        } else {
            throw new IllegalArgumentException("Источник «" + part.name() + "» — не картинка JPEG или PNG");
        }

        float width = image.getWidth() * POINTS_PER_PIXEL;
        float height = image.getHeight() * POINTS_PER_PIXEL;
        boolean turned = switch (orientation) {
            case LEFT_TOP, RIGHT_TOP, RIGHT_BOTTOM, LEFT_BOTTOM -> true;
            default -> false;
        };
        PDPage page = new PDPage(turned ? new PDRectangle(height, width) : new PDRectangle(width, height));
        try (PDPageContentStream drawing = new PDPageContentStream(result, page)) {
            drawing.transform(placement(orientation, width, height));
            drawing.drawImage(image, 0, 0, width, height);
        }
        return page;
    }

    /**
     * Матрица, переводящая картинку, нарисованную в прямоугольнике
     * {@code w × h} как она хранится, в то положение, в каком её видел
     * снимавший. Координаты PDF — ось Y вверх.
     */
    private static Matrix placement(Orientation orientation, float w, float h) {
        return switch (orientation) {
            case TOP_LEFT -> new Matrix();
            case TOP_RIGHT -> new Matrix(-1, 0, 0, 1, w, 0);
            case BOTTOM_RIGHT -> new Matrix(-1, 0, 0, -1, w, h);
            case BOTTOM_LEFT -> new Matrix(1, 0, 0, -1, 0, h);
            case LEFT_TOP -> new Matrix(0, -1, -1, 0, h, w);
            case RIGHT_TOP -> new Matrix(0, -1, 1, 0, 0, w);
            case RIGHT_BOTTOM -> new Matrix(0, 1, 1, 0, 0, 0);
            case LEFT_BOTTOM -> new Matrix(0, 1, -1, 0, h, 0);
        };
    }

    private static Orientation orientationOf(byte[] jpeg, String name) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
        if (!readers.hasNext()) {
            return Orientation.TOP_LEFT;
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
            reader.setInput(in);
            Orientation orientation = ExifUtils.getExifOrientation(reader, 0);
            return orientation == null ? Orientation.TOP_LEFT : orientation;
        } catch (IOException e) {
            throw new IllegalArgumentException("Картинка «" + name + "» не разбирается");
        } finally {
            reader.dispose();
        }
    }

    static boolean isJpeg(byte[] content) {
        return content.length > 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8;
    }

    static boolean isPng(byte[] content) {
        return content.length > 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P'
                && content[2] == 'N' && content[3] == 'G';
    }

    private static void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException ignored) {
            // Результат уже собран или сборка уже упала — закрытие источника
            // ничего к этому не добавит.
        }
    }
}
