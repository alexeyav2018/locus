package ru.locus.file;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки хранилища файлов.
 *
 * Умолчания подобраны так, чтобы {@code mvn spring-boot:run} поднимался без
 * единого заданного снаружи значения: работает папка на диске. Боевые значения
 * объектного хранилища приходят из окружения (элемент бэклога
 * {@code deployment-backup}) и здесь намеренно пусты — неверное умолчание
 * тихо увело бы файлы не туда.
 *
 * @param storage какая реализация работает: {@code local} или {@code object}
 * @param linkTtl сколько живёт выданная ссылка
 */
@ConfigurationProperties(prefix = "locus.file")
public record FileStorageProperties(
        @DefaultValue("local") String storage,
        @DefaultValue("10m") Duration linkTtl,
        @DefaultValue Local local,
        @DefaultValue ObjectStorage object,
        @DefaultValue Image image) {

    public static final String LOCAL = "local";
    public static final String OBJECT = "object";

    /**
     * @param directory куда складываются файлы
     * @param secret    ключ подписи ссылок; пустой означает «сгенерировать
     *                  при старте» — тогда ссылки не переживают перезапуск,
     *                  что для разработки приемлемо
     */
    public record Local(
            @DefaultValue("./.locus-files") Path directory,
            @DefaultValue("") String secret) {
    }

    public record ObjectStorage(
            @DefaultValue("") String endpoint,
            @DefaultValue("") String region,
            @DefaultValue("") String bucket,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey) {
    }

    /**
     * @param maxSide предел по большей стороне изображения, точек
     * @param quality качество JPEG, от 0 до 1
     */
    public record Image(
            @DefaultValue("2000") int maxSide,
            @DefaultValue("0.8") double quality) {
    }
}
