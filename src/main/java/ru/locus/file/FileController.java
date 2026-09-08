package ru.locus.file;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.locus.Addresses;

/**
 * Отдача файла по подписанной ссылке — только для файловой реализации
 * хранилища: у объектного варианта эту работу делает само хранилище, и запрос
 * до приложения не доходит.
 *
 * Контроллер тонкий: разбирает адрес, спрашивает хранилище и отдаёт байты.
 * Решения о правах здесь нет и быть не может — пропуском служит подпись,
 * выданная тем, кто эти права уже проверил.
 */
@RestController
@ConditionalOnProperty(name = "locus.file.storage", havingValue = FileStorageProperties.LOCAL, matchIfMissing = true)
public class FileController {

    private final LocalFileStorage storage;

    public FileController(LocalFileStorage storage) {
        this.storage = storage;
    }

    @GetMapping(Addresses.FILE + "/{key}")
    public ResponseEntity<byte[]> serve(
            @PathVariable String key,
            @RequestParam(required = false) Long expires,
            @RequestParam(required = false) String signature) {

        FileKey fileKey;
        try {
            fileKey = new FileKey(key);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Отсутствующие срок или подпись — тот же отказ, что и неверные:
        // ссылка без подписи не должна выглядеть как особый случай.
        if (expires == null || signature == null
                || !storage.isValidLink(fileKey, Instant.ofEpochSecond(expires), signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Пустой результат — «файла нет»; отличить «никогда не существовал»
        // от «удалён» по ответу нельзя, и это требование, а не упущение.
        return storage.read(fileKey)
                .map(content -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, FileType.contentTypeFor(fileKey))
                        .body(content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
