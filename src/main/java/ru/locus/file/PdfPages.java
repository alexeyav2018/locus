package ru.locus.file;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Страницы PDF картинками — для страницы просмотра (ADR-0052). Чистые
 * функции над байтами файла: откуда байты и кому их можно видеть, решает
 * вызывающий.
 *
 * <p>Страница видна так же, как в просмотрщике PDF: по видимой области
 * и с поворотом. Ширина картинки — не больше {@value #MAX_WIDTH} пикселей
 * и не больше {@value #MAX_DPI} dpi: столбец заполняет экран по ширине,
 * а мелкая страница не раздувается.
 *
 * <p>Неразбираемый и закрытый паролем PDF здесь не ошибка, а пустой
 * результат: страница просмотра в этом случае говорит, что показать
 * не удалось, и оставляет кнопку открыть файл.
 *
 * <p>С отрисовкой рамки сборки ({@code PdfAssembly.preview}) этот класс
 * не связан: у рамки свои пределы и своя связь с долями страницы (ADR-0045).
 */
public final class PdfPages {

    /** Сколько первых страниц показывает страница просмотра (ADR-0052). */
    public static final int SHOWN = 20;

    static final int MAX_WIDTH = 1600;
    static final int MAX_DPI = 200;

    private PdfPages() {
    }

    /**
     * Число страниц и размеры картинок первых {@value #SHOWN} из них.
     *
     * @return пусто, если файл не разбирается как PDF или закрыт паролем
     */
    public static Optional<PdfOutline> outline(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            List<PdfOutline.PageSize> shown = new ArrayList<>();
            for (int index = 0; index < Math.min(pages, SHOWN); index++) {
                shown.add(sizeOf(document.getPage(index)));
            }
            return Optional.of(new PdfOutline(pages, shown));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Страница картинкой JPEG.
     *
     * @param page номер страницы с единицы
     * @return пусто, если страницы с таким номером нет или файл не разбирается
     */
    public static Optional<byte[]> render(byte[] pdf, int page) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (page < 1 || page > document.getNumberOfPages()) {
                return Optional.empty();
            }
            float scale = scaleOf(document.getPage(page - 1));
            BufferedImage image = new PDFRenderer(document).renderImage(page - 1, scale, ImageType.RGB);
            return Optional.of(jpeg(image));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Размер картинки так же, как его считает {@link PDFRenderer}: видимая
     * область, умноженная на масштаб и усечённая вниз, а при повороте
     * на четверть оборота — стороны местами.
     */
    private static PdfOutline.PageSize sizeOf(PDPage page) {
        PDRectangle visible = page.getCropBox();
        float scale = scaleOf(page);
        int width = (int) Math.max(Math.floor(visible.getWidth() * scale), 1);
        int height = (int) Math.max(Math.floor(visible.getHeight() * scale), 1);
        return quarterTurned(page) ? new PdfOutline.PageSize(height, width) : new PdfOutline.PageSize(width, height);
    }

    /** Масштаб от пунктов к пикселям: предел по ширине видимой страницы и по dpi. */
    private static float scaleOf(PDPage page) {
        PDRectangle visible = page.getCropBox();
        float shownWidth = quarterTurned(page) ? visible.getHeight() : visible.getWidth();
        return Math.min(MAX_WIDTH / shownWidth, MAX_DPI / 72f);
    }

    private static boolean quarterTurned(PDPage page) {
        return Math.floorMod(page.getRotation(), 180) == 90;
    }

    private static byte[] jpeg(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
