package ru.locus.file;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import ru.locus.Addresses;

/**
 * Хранилище на папке диска — для разработки и тестов (ADR-0021, отвергнутая
 * как основная, но оставленная реализация).
 *
 * Ссылку подписывает само, адресом приложения: {@code /file/<ключ>?expires=…
 * &signature=…}. Так поведение совпадает с объектным вариантом, где запрос
 * идёт мимо приложения по подписанному адресу, — и одни и те же проверки
 * что-то говорят про обе реализации.
 *
 * Возвращается относительный адрес: хост и порт хранилищу неизвестны и знать
 * их ему незачем — браузер разрешит ссылку относительно текущего адреса.
 */
public class LocalFileStorage implements FileStorage {

    private final Path directory;
    private final LinkSignature signature;
    private final Duration linkTtl;
    private final Clock clock;

    public LocalFileStorage(Path directory, LinkSignature signature, Duration linkTtl, Clock clock) {
        this.directory = directory;
        this.signature = signature;
        this.linkTtl = linkTtl;
        this.clock = clock;
    }

    @Override
    public FileKey put(byte[] content, String contentType) {
        FileKey key = FileKey.generated(contentType);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(key.value()), content);
        } catch (IOException e) {
            throw FileStorageUnavailableException.reported("Не записать файл в " + directory.toAbsolutePath(), e);
        }
        return key;
    }

    @Override
    public URI temporaryLink(FileKey key) {
        Instant expires = clock.instant().plus(linkTtl);
        return URI.create(Addresses.FILE + "/" + key.value()
                + "?expires=" + expires.getEpochSecond()
                + "&signature=" + signature.sign(key, expires));
    }

    @Override
    public void delete(FileKey key) {
        try {
            Files.deleteIfExists(directory.resolve(key.value()));
        } catch (IOException e) {
            throw FileStorageUnavailableException.reported("Не удалить файл из " + directory.toAbsolutePath(), e);
        }
    }

    /**
     * Чтение содержимого — не часть {@link FileStorage}: наружу файл уходит
     * только по подписанной ссылке. Этим методом пользуется контроллер,
     * обслуживающий такую ссылку, и никто больше.
     *
     * Пустой результат означает «файла нет» — и не различает «никогда
     * не существовал» и «удалён».
     */
    Optional<byte[]> read(FileKey key) {
        try {
            return Optional.of(Files.readAllBytes(directory.resolve(key.value())));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw FileStorageUnavailableException.reported("Не прочитать файл из " + directory.toAbsolutePath(), e);
        }
    }

    boolean isValidLink(FileKey key, Instant expires, String providedSignature) {
        return !clock.instant().isAfter(expires) && signature.isValid(key, expires, providedSignature);
    }
}
