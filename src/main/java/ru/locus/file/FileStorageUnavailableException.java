package ru.locus.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Хранилище не отвечает.
 *
 * Отдельный тип, а не общий сбой: «файла нет» и «хранилище недоступно» —
 * разные события, и второе не должно выглядеть как первое. Хранилище —
 * единственная внешняя зависимость системы (architecture.md, «Внешние
 * зависимости»); её отказ делает недоступными файлы, но не данные, и отличить
 * одно от другого нужно и в журнале, и на экране.
 */
public class FileStorageUnavailableException extends RuntimeException {

    private static final Logger LOG = LoggerFactory.getLogger(FileStorageUnavailableException.class);

    public FileStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Создаёт исключение, записав причину в журнал.
     *
     * Обычно «залогировать и бросить» — лишнее удвоение: пишет тот, кто
     * обрабатывает. Здесь иначе по двум причинам. Отказ внешней зависимости
     * — единственное место, где причина видна целиком (адрес, бакет, ответ
     * сервиса), а выше по стеку от неё останется только сообщение. И журнал
     * прямо требуется спецификацией: страница может показать учителю
     * «файлы временно недоступны», но разбираться, почему именно, придётся
     * по журналу.
     */
    static FileStorageUnavailableException reported(String message, Throwable cause) {
        LOG.error(message, cause);
        return new FileStorageUnavailableException(message, cause);
    }
}
