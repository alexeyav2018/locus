package ru.locus.file;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Пережатие изображения при загрузке.
 *
 * Отдельный класс, а не часть {@link FileStorage}: хранилище не содержит
 * знания о том, что за файл ему дали (architecture.md, таблица слоёв).
 * Пережимается снимок, но не PDF — условие и решение задачи хранятся ровно
 * такими, какими их дал Администратор, и решает это вызывающий, а не
 * хранилище.
 *
 * Разворот по сведениям о съёмке обязателен: телефон пишет ориентацию
 * в EXIF, а не поворотом пикселей. Забыть его — тихая ошибка: файл цел,
 * объём уменьшился, а фотография лежит на боку, и обнаружит это учитель,
 * а не тест. Разворот делает Thumbnailator; ради него библиотека и взята.
 */
public class ImageCompression {

    private final int maxSide;
    private final double quality;

    public ImageCompression(int maxSide, double quality) {
        this.maxSide = maxSide;
        this.quality = quality;
    }

    /**
     * Уменьшает изображение до предела по большей стороне и возвращает JPEG.
     * Снимок, уже укладывающийся в предел, не растягивается — увеличенный
     * весит больше исходного и ничего не добавляет, — но разворачивается
     * по сведениям о съёмке наравне с крупным: иначе ориентация зависела бы
     * от размера.
     *
     * @return пережатое изображение и его тип содержимого
     */
    public Compressed compress(byte[] image) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try {
            Thumbnails.Builder<? extends InputStream> thumbnail =
                    Thumbnails.of(new ByteArrayInputStream(image));
            if (largestSideOf(image) > maxSide) {
                thumbnail.size(maxSide, maxSide).keepAspectRatio(true);
            } else {
                thumbnail.scale(1.0);
            }
            thumbnail.outputQuality(quality)
                    .outputFormat("jpg")
                    .toOutputStream(compressed);
        } catch (IOException e) {
            throw new UncheckedIOException("Не пережать изображение", e);
        }
        return new Compressed(compressed.toByteArray(), FileType.JPEG);
    }

    /**
     * Размеры читаются до разворота — и это не важно: поворот на прямой угол
     * меняет стороны местами, а наибольшую из них не меняет.
     */
    private int largestSideOf(byte[] image) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) {
            throw new IOException("Содержимое не разбирается как изображение");
        }
        return Math.max(source.getWidth(), source.getHeight());
    }

    public record Compressed(byte[] content, String contentType) {
    }
}
