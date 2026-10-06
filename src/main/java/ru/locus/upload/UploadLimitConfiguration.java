package ru.locus.upload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Двухуровневый предел загрузки (ADR-0041): контейнер пропускает до
 * предела инструмента сборки, общий предел проверяет
 * {@link UploadLimitInterceptor} на всех обработчиках сразу.
 */
@Configuration
@EnableConfigurationProperties(UploadLimitProperties.class)
public class UploadLimitConfiguration implements WebMvcConfigurer {

    private final UploadLimitProperties limits;

    public UploadLimitConfiguration(UploadLimitProperties limits) {
        this.limits = limits;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UploadLimitInterceptor(limits));
    }
}
