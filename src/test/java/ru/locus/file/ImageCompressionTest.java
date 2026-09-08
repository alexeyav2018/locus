package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Пережатие изображений: требование «Изображение пережимается при загрузке».
 *
 * Проверяются оба сценария спеки — крупный снимок уменьшается, а снимок,
 * сделанный повёрнутой камерой, не остаётся лежащим на боку. Второе особенно
 * важно: телефон пишет ориентацию сведениями о съёмке, а не поворотом
 * пикселей, и забытый разворот ничего не роняет — фотография просто лежит
 * боком, и видит это учитель, а не сборка.
 */
class ImageCompressionTest {

    private static final int MAX_SIDE = 1000;

    private final ImageCompression compression = new ImageCompression(MAX_SIDE, 0.8);

    @Test
    void largePhotoIsScaledDown() {
        byte[] original = resource("large.jpg");

        ImageCompression.Compressed compressed = compression.compress(original);

        assertThat(compressed.content().length).isLessThan(original.length);
        assertThat(compressed.contentType()).isEqualTo(FileType.JPEG);

        BufferedImage result = read(compressed.content());
        assertThat(Math.max(result.getWidth(), result.getHeight())).isLessThanOrEqualTo(MAX_SIDE);
    }

    @Test
    void photoTakenWithARotatedCameraIsUprightAfterwards() {
        byte[] original = resource("rotated.jpg");

        // Пиксели лежат горизонтально: 600 в ширину, 400 в высоту.
        BufferedImage before = read(original);
        assertThat(before.getWidth()).isGreaterThan(before.getHeight());

        BufferedImage after = read(compression.compress(original).content());

        // После разворота по сведениям о съёмке снимок стоит вертикально.
        assertThat(after.getHeight()).isGreaterThan(after.getWidth());
    }

    @Test
    void smallPhotoIsNotStretched() {
        byte[] original = resource("rotated.jpg");
        BufferedImage before = read(original);

        BufferedImage after = read(compression.compress(original).content());

        assertThat(Math.max(after.getWidth(), after.getHeight()))
                .isEqualTo(Math.max(before.getWidth(), before.getHeight()));
    }

    /**
     * Пережимать или нет — решает вызывающий, и решает по типу содержимого.
     * PDF под это правило не подпадает: условие и решение задачи хранятся
     * ровно такими, какими их дал Администратор. Побайтовое совпадение
     * положенного и прочитанного проверяет контракт хранилища.
     */
    @Test
    void pdfIsNotAnImageAndSoIsNeverCompressed() {
        assertThat(FileType.isImage(FileType.PDF)).isFalse();
        assertThat(FileType.isImage(FileType.BINARY)).isFalse();
        assertThat(FileType.isImage(FileType.JPEG)).isTrue();
        assertThat(FileType.isImage("image/png; charset=binary")).isTrue();
        assertThat(FileType.isImage(null)).isFalse();
    }

    private static byte[] resource(String name) {
        try (InputStream stream = ImageCompressionTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Нет тестового изображения " + name);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Не прочитать тестовое изображение " + name, e);
        }
    }

    private static BufferedImage read(byte[] image) {
        try {
            return ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            throw new UncheckedIOException("Не разобрать изображение", e);
        }
    }
}
