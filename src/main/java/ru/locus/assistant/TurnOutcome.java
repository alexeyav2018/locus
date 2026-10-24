package ru.locus.assistant;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Исход Реплики — то, что записывается разом при её завершении
 * (design.md agent-runner, Р8). Состояние — конечное.
 */
public record TurnOutcome(TurnState state,
                          String answer,
                          String cause,
                          Long inputTokens,
                          Long outputTokens,
                          BigDecimal costUsd,
                          Instant finishedAt) {

    public TurnOutcome {
        if (state == null || state.running()) {
            throw new IllegalArgumentException("Исход Реплики — конечное состояние, а не " + state);
        }
        if (finishedAt == null) {
            throw new IllegalArgumentException("У исхода Реплики есть время завершения");
        }
    }
}
