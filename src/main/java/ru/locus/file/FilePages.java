package ru.locus.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Страницы PDF из хранилища картинками — для страницы просмотра
 * (ADR-0052).
 *
 * Права здесь не решаются, как и в самом {@link FileStorage}: ключ
 * приходит из записи, которую вызывающий уже достал своим сервисом
 * с проверкой владельца. Нет файла в хранилище — пустой результат,
 * а не ошибка: для страницы просмотра это тот же «не показать»,
 * что и неразбираемый PDF.
 *
 * @throws FileStorageUnavailableException из каждого чтения, если
 *         хранилище не отвечает: недоступность не выглядит отсутствием
 */
@Component
public class FilePages {

    private final FileStorage storage;

    public FilePages(FileStorage storage) {
        this.storage = storage;
    }

    /** Число страниц и размеры первых; пусто — файла нет или он не разбирается. */
    public Optional<PdfOutline> outline(FileKey key) {
        return storage.read(key).flatMap(PdfPages::outline);
    }

    /** Страница с единицы картинкой JPEG; пусто — нет файла или такой страницы. */
    public Optional<byte[]> page(FileKey key, int page) {
        return storage.read(key).flatMap(pdf -> PdfPages.render(pdf, page));
    }

    /**
     * Метка содержимого для {@code ETag}: хеш ключа, а не сам ключ — ключ
     * файла Работы наружу не выходит. Файл по ключу не меняется, а замена
     * файла даёт новый ключ, поэтому метки хватает, чтобы не рисовать
     * страницу заново и не показать заменённое из кэша.
     */
    public String etag(FileKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.value().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 обязан быть в любой Java", e);
        }
    }
}
