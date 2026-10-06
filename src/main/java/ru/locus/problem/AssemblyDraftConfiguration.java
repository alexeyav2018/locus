package ru.locus.problem;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Черновики сборки: настройки и очистка при старте (ADR-0044).
 *
 * Черновик не переживает перезапуск: незавершённая форма перезапуска
 * тоже не переживает, а рабочая папка не должна копить исходники,
 * брошенные оборванной загрузкой или упавшим процессом.
 */
@Configuration
@EnableConfigurationProperties(AssemblyDraftProperties.class)
public class AssemblyDraftConfiguration {

    @Bean
    public ApplicationRunner assemblyDraftsClearedOnStartup(AssemblyDraftService drafts) {
        return arguments -> drafts.clearAll();
    }
}
