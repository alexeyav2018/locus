package ru.locus;

/**
 * Версия образа MinIO — S3-совместимого хранилища, на котором проверяется
 * объектная реализация {@code FileStorage}.
 *
 * Задаётся здесь и больше нигде. В отличие от PostgreSQL, MinIO не описан
 * в {@code compose.yaml}: локальная разработка идёт на файловой реализации,
 * и поднимать контейнер при каждом запуске приложения незачем. Место для
 * версии от этого меняется, а правило — нет: она записана один раз, иначе
 * копии разъедутся молча.
 */
public final class MinioImage {

    private static final String IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z";

    private MinioImage() {
    }

    public static String name() {
        return IMAGE;
    }
}
