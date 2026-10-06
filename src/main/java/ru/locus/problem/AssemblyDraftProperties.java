package ru.locus.problem;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки черновиков сборки (ADR-0041).
 *
 * @param directory рабочая папка, куда ложатся загруженные исходники;
 *                  при старте очищается целиком — делить её ни с чем нельзя
 * @param ttl       сколько живёт неиспользованный черновик; с запасом
 *                  длиннее сессии входа, поэтому черновик старше срока
 *                  гарантированно брошен (design.md, «Уборка»)
 */
@ConfigurationProperties(prefix = "locus.problem.draft")
public record AssemblyDraftProperties(
        @DefaultValue("./.locus-drafts") Path directory,
        @DefaultValue("24h") Duration ttl) {
}
