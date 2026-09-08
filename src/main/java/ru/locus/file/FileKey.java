package ru.locus.file;

import java.util.UUID;

/**
 * Ключ файла в хранилище — отдельный тип, а не голая строка.
 *
 * Ключ выдаёт хранилище, а не тот, кто кладёт файл. Осмысленный ключ вида
 * «problems/12/condition.pdf» угадывается, а вместе с ним угадывается и адрес;
 * кроме того, за уникальность пришлось бы отвечать вызывающему. Поэтому
 * значение — случайный UUID, а расширение несёт тип содержимого: хранить тип
 * отдельно (соседним файлом, таблицей, метаданными объекта) не приходится,
 * и обе реализации делают это одинаково.
 *
 * Разделитель пути в значении запрещён: ключ подставляется в имя файла
 * на диске, и «../» в нём увело бы запись за пределы каталога хранилища.
 */
public record FileKey(String value) {

    public FileKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Ключ файла не может быть пустым");
        }
        if (value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Ключ файла не может содержать разделитель пути: " + value);
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException("Ключ файла не может содержать переход вверх: " + value);
        }
    }

    /**
     * Новый ключ для файла указанного типа: случайное имя и расширение,
     * по которому тип потом восстанавливается при отдаче.
     */
    public static FileKey generated(String contentType) {
        return new FileKey(UUID.randomUUID() + "." + FileType.extensionFor(contentType));
    }

    /**
     * Расширение ключа — без точки. По нему {@link FileType} восстанавливает
     * тип содержимого, когда файл отдаётся.
     */
    public String extension() {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1);
    }
}
