package ru.locus.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Общий предел загрузки — для всех обработчиков, кроме отмеченных
 * {@link LargeUpload}.
 *
 * Предел контейнера ({@code spring.servlet.multipart}) поднят до предела
 * инструмента сборки, поэтому общий держится не контейнером, а
 * {@link UploadLimitInterceptor}.
 *
 * @param maxFileSize    наибольший файл
 * @param maxRequestSize наибольший запрос со всеми файлами разом
 */
@ConfigurationProperties(prefix = "locus.upload")
public record UploadLimitProperties(
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue("100MB") DataSize maxRequestSize) {
}
