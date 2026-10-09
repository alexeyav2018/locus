package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Страницы PDF из хранилища — на файловой реализации: отрисовку проверяет
 * {@link PdfPagesTest}, здесь — что ключ доходит до содержимого и что
 * отсутствующий файл не ошибка.
 */
class FilePagesTest {

    @TempDir
    Path directory;

    private FileStorage storage;
    private FilePages pages;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(directory, new LinkSignature("секрет для теста"), Duration.ofMinutes(10),
                Clock.systemUTC());
        pages = new FilePages(storage);
    }

    @Test
    void storedPdfIsOutlinedAndRendered() throws IOException {
        FileKey key = storage.put(PdfPagesTest.pdf(2, PDRectangle.A4, 0), FileType.PDF);

        assertThat(pages.outline(key)).hasValueSatisfying(outline -> assertThat(outline.pages()).isEqualTo(2));
        assertThat(pages.page(key, 2)).isPresent();
        assertThat(pages.page(key, 3)).isEmpty();
    }

    @Test
    void missingFileIsEmpty() throws IOException {
        FileKey key = storage.put(PdfPagesTest.pdf(1, PDRectangle.A4, 0), FileType.PDF);
        storage.delete(key);

        assertThat(pages.outline(key)).isEmpty();
        assertThat(pages.page(key, 1)).isEmpty();
    }

    @Test
    void etagIsStableForTheKeyAndDoesNotRevealIt() throws IOException {
        FileKey key = storage.put(PdfPagesTest.pdf(1, PDRectangle.A4, 0), FileType.PDF);
        FileKey replaced = storage.put(PdfPagesTest.pdf(1, PDRectangle.A4, 0), FileType.PDF);

        assertThat(pages.etag(key)).isEqualTo(pages.etag(key));
        assertThat(pages.etag(key)).isNotEqualTo(pages.etag(replaced));
        assertThat(pages.etag(key)).doesNotContain(key.value().substring(0, 8));
    }
}
