package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * Контракт хранилища на файловой реализации — той, что работает
 * при разработке.
 *
 * Настройки задаются свойствами теста, а не вторым {@code application.yaml}
 * в тестовых ресурсах: файл по тому же пути на classpath не дополняет
 * основной, а перекрывает его целиком (antipatterns.md).
 *
 * Срок жизни ссылки укорочен до двух секунд — иначе сценарий «Истёкшая
 * ссылка» проверить нечем: подождать десять минут прогон не может, а
 * подменить время у объектного хранилища нельзя, и контракт должен быть один.
 */
@TestPropertySource(properties = {
        "locus.file.storage=local",
        "locus.file.link-ttl=2s",
        "locus.file.local.directory=${java.io.tmpdir}/locus-file-storage-test",
        "locus.file.local.secret=секрет для теста"
})
class LocalFileStorageTest extends FileStorageContractTest {

    /**
     * Ответ сам называет файл его ключом. Без этого Spring дописывает к ссылке
     * на {@code .pdf} защитное {@code inline;filename=f.txt}, и Яндекс.Браузер
     * отказывается показывать PDF, а скачивает текстовый файл. Только у файловой
     * реализации: объектная ссылка до приложения не доходит.
     */
    @Test
    void pdfIsServedInlineUnderItsKey() {
        FileKey key = storage.put("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), FileType.PDF);

        HttpResponse<byte[]> response = get(storage.temporaryLink(key));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(disposition(response)).isEqualTo(ContentDisposition.inline().filename(key.value()).build());
        assertThat(key.value()).endsWith(".pdf");
    }

    @Test
    void imageIsServedInlineUnderItsKey() {
        FileKey key = storage.put(new byte[] {1, 2, 3}, FileType.JPEG);

        HttpResponse<byte[]> response = get(storage.temporaryLink(key));

        assertThat(disposition(response)).isEqualTo(ContentDisposition.inline().filename(key.value()).build());
        assertThat(key.value()).endsWith(".jpg");
    }

    private static ContentDisposition disposition(HttpResponse<?> response) {
        return ContentDisposition.parse(response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION).orElseThrow());
    }
}
