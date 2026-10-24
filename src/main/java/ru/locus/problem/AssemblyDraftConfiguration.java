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
 *
 * Не переживает перезапуск и Черновик Задачи, держащий Черновики сборки
 * (ADR-0059): уборку при старте ведёт он, потому что держимые им исходники
 * можно удалить только после него.
 */
@Configuration
@EnableConfigurationProperties({AssemblyDraftProperties.class, ProblemDraftProperties.class})
public class AssemblyDraftConfiguration {

    @Bean
    public ApplicationRunner assemblyDraftsClearedOnStartup(ProblemDraftService drafts) {
        return arguments -> drafts.clearAll();
    }
}
