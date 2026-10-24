package ru.locus.problem;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки Черновиков Задачи (ADR-0059).
 *
 * @param ttl сколько живёт черновик от последней правки; неделя, а не сутки
 *            Черновика сборки: вычитка листа может растянуться на несколько
 *            вечеров, а держит черновик лишь место на диске
 *            (problem-intake, design.md, Р4)
 */
@ConfigurationProperties(prefix = "locus.problem.intake")
public record ProblemDraftProperties(@DefaultValue("7d") Duration ttl) {
}
