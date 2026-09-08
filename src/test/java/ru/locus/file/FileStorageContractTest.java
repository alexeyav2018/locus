package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.IntegrationTest;

/**
 * Контракт хранилища файлов — общий для обеих реализаций.
 *
 * Здесь нет ни одного упоминания того, какая реализация проверяется: её
 * подставляет наследник. Смысл в этом и есть — единственная реализация,
 * которая повезёт файлы учеников, обязана проходить те же проверки, что
 * и удобная файловая, иначе боевой код остаётся единственным непроверенным
 * (ADR-0021).
 *
 * Содержимое читается только по выданной ссылке, обращением по HTTP: метода
 * «прочитать по ключу» у хранилища нет, и проверять надо ровно тот путь,
 * которым файл получит браузер учителя.
 */
abstract class FileStorageContractTest extends IntegrationTest {

    private static final byte[] CONTENT = "содержимое файла".getBytes(StandardCharsets.UTF_8);

    @Autowired
    protected FileStorage storage;

    @Value("${locus.file.link-ttl}")
    private Duration linkTtl;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void storedFileIsReadBackThroughItsLink() {
        FileKey key = storage.put(CONTENT, FileType.PDF);

        HttpResponse<byte[]> response = get(storage.temporaryLink(key));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(CONTENT);
    }

    @Test
    void twoFilesOfTheSameKindGetDifferentKeys() {
        byte[] other = "другое содержимое".getBytes(StandardCharsets.UTF_8);

        FileKey first = storage.put(CONTENT, FileType.PDF);
        FileKey second = storage.put(other, FileType.PDF);

        assertThat(first).isNotEqualTo(second);
        assertThat(get(storage.temporaryLink(first)).body()).isEqualTo(CONTENT);
        assertThat(get(storage.temporaryLink(second)).body()).isEqualTo(other);
    }

    @Test
    void missingFileIsIndistinguishableFromDeletedOne() {
        FileKey neverStored = FileKey.generated(FileType.PDF);
        FileKey deleted = storage.put(CONTENT, FileType.PDF);
        storage.delete(deleted);

        HttpResponse<byte[]> neverStoredResponse = get(storage.temporaryLink(neverStored));
        HttpResponse<byte[]> deletedResponse = get(storage.temporaryLink(deleted));

        assertThat(neverStoredResponse.statusCode()).isNotEqualTo(200);
        assertThat(deletedResponse.statusCode()).isEqualTo(neverStoredResponse.statusCode());
    }

    @Test
    void expiredLinkGivesNothing() throws InterruptedException {
        FileKey key = storage.put(CONTENT, FileType.PDF);
        URI link = storage.temporaryLink(key);

        Thread.sleep(linkTtl.plusMillis(1500).toMillis());

        assertThat(get(link).statusCode()).isNotEqualTo(200);
    }

    @Test
    void linkWithoutSignatureGivesNothing() {
        FileKey key = storage.put(CONTENT, FileType.PDF);

        assertThat(get(withoutSignature(storage.temporaryLink(key))).statusCode()).isNotEqualTo(200);
    }

    @Test
    void linkWithForgedSignatureGivesNothing() {
        FileKey key = storage.put(CONTENT, FileType.PDF);

        assertThat(get(withForgedSignature(storage.temporaryLink(key))).statusCode()).isNotEqualTo(200);
    }

    @Test
    void deletedFileIsNotReadableThroughALinkIssuedEarlier() {
        FileKey key = storage.put(CONTENT, FileType.PDF);
        URI issuedBeforeDeletion = storage.temporaryLink(key);

        storage.delete(key);

        assertThat(get(issuedBeforeDeletion).statusCode()).isNotEqualTo(200);
    }

    @Test
    void deletingATwiceDeletedFileIsNotAnError() {
        FileKey key = storage.put(CONTENT, FileType.PDF);
        storage.delete(key);

        assertThatCode(() -> storage.delete(key)).doesNotThrowAnyException();
        assertThatCode(() -> storage.delete(FileKey.generated(FileType.PDF))).doesNotThrowAnyException();
    }

    /**
     * Ссылка файловой реализации относительна — хост и порт ей неизвестны;
     * ссылка объектного хранилища абсолютна и разрешением не меняется.
     */
    private HttpResponse<byte[]> get(URI link) {
        URI target = URI.create("http://localhost:" + port).resolve(link);
        try {
            return http.send(HttpRequest.newBuilder(target).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Не обратиться по ссылке " + target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Обращение по ссылке прервано", e);
        }
    }

    /**
     * Параметр подписи называется по-разному у файловой реализации
     * и у объектного хранилища, поэтому ищется по имени, а не по месту.
     */
    private URI withoutSignature(URI link) {
        return withQuery(link, parameters(link).stream()
                .filter(parameter -> !isSignature(parameter))
                .collect(Collectors.joining("&")));
    }

    private URI withForgedSignature(URI link) {
        List<String> forged = new ArrayList<>();
        for (String parameter : parameters(link)) {
            forged.add(isSignature(parameter) ? forge(parameter) : parameter);
        }
        return withQuery(link, String.join("&", forged));
    }

    private static boolean isSignature(String parameter) {
        return parameter.toLowerCase().startsWith("signature=")
                || parameter.toLowerCase().startsWith("x-amz-signature=");
    }

    /**
     * Меняется последний знак значения: длина подписи остаётся верной,
     * а сама подпись — нет.
     */
    private static String forge(String parameter) {
        char last = parameter.charAt(parameter.length() - 1);
        char replacement = last == '0' ? '1' : '0';
        return parameter.substring(0, parameter.length() - 1) + replacement;
    }

    private static List<String> parameters(URI link) {
        String query = link.getRawQuery();
        return query == null ? List.of() : List.of(query.split("&"));
    }

    private static URI withQuery(URI link, String query) {
        String withoutQuery = link.toString().split("\\?")[0];
        return URI.create(query.isEmpty() ? withoutQuery : withoutQuery + "?" + query);
    }
}
