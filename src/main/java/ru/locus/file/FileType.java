package ru.locus.file;

import java.util.Map;

/**
 * Соответствие между типом содержимого и расширением ключа.
 *
 * Нужно в обе стороны: при укладке — чтобы выбрать расширение, при отдаче —
 * чтобы сказать браузеру, что он получил. Знание одно, и живёт оно в одном
 * месте: разъехавшись, оно дало бы PDF, который браузер сохраняет вместо того,
 * чтобы показать.
 *
 * Система хранит два потока (ADR-0021): PDF задач и теории, изображения
 * работ. Всё прочее укладывается как двоичный поток — отказывать в укладке
 * хранилище не должно, оно про содержимое ничего не решает.
 */
public final class FileType {

    public static final String PDF = "application/pdf";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String BINARY = "application/octet-stream";

    private static final Map<String, String> EXTENSIONS = Map.of(
            PDF, "pdf",
            JPEG, "jpg",
            PNG, "png");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", PDF,
            "jpg", JPEG,
            "jpeg", JPEG,
            "png", PNG);

    private FileType() {
    }

    public static String extensionFor(String contentType) {
        return EXTENSIONS.getOrDefault(normalized(contentType), "bin");
    }

    public static String contentTypeFor(FileKey key) {
        return CONTENT_TYPES.getOrDefault(key.extension().toLowerCase(), BINARY);
    }

    /**
     * Изображения пережимаются при загрузке, PDF — никогда: условие и решение
     * задачи хранятся ровно такими, какими их дал Администратор.
     */
    public static boolean isImage(String contentType) {
        String type = normalized(contentType);
        return JPEG.equals(type) || PNG.equals(type);
    }

    /**
     * Тип содержимого может прийти с параметрами («image/jpeg; charset=...»)
     * и в любом регистре — сравнивать надо приведённый.
     */
    private static String normalized(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return type.trim().toLowerCase();
    }
}
