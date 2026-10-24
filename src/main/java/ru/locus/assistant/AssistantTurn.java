package ru.locus.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import ru.locus.user.UserId;

/**
 * Реплика — один обмен в Разговоре: запрос учителя и исход процесса
 * Помощника (ADR-0054; design.md agent-runner, Р8).
 *
 * <p>Пока Реплика идёт, заполнены только запрос, состояние и начало;
 * остальное приходит с исходом. Токены и стоимость есть, когда процесс успел
 * выдать итог, — в том числе у «прервано по шагам»; у зависшей
 * и остановленной их может не быть.
 *
 * @param answer       ответ Помощника; есть у «готово»
 * @param cause        причина у неудачи и прерываний; у «готово» пусто
 * @param inputTokens  токены входа вместе с кешированными; {@code null}, если итога не было
 * @param outputTokens токены выхода; {@code null}, если итога не было
 * @param costUsd      стоимость в долларах по итогу Claude Code; {@code null}, если итога не было
 */
public record AssistantTurn(AssistantTurnId id,
                            UserId owner,
                            AssistantConversationId conversation,
                            String prompt,
                            TurnState state,
                            String answer,
                            String cause,
                            Long inputTokens,
                            Long outputTokens,
                            BigDecimal costUsd,
                            Instant startedAt,
                            Instant finishedAt) {
}
