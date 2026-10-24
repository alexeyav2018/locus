package ru.locus.assistant;

import java.time.Instant;

/**
 * Разговор в списке прежних (design.md assistant-chat, Р10): когда в нём
 * говорили последний раз и с чего он начался.
 *
 * @param lastTurnAt  начало последней Реплики Разговора
 * @param firstPrompt запрос первой Реплики целиком; обрезает {@link #opening()}
 */
public record ConversationSummary(AssistantConversationId id, Instant lastTurnAt, String firstPrompt) {

    /** Сколько знаков первого запроса видно в списке. */
    static final int OPENING_LENGTH = 80;

    /** Начало первого запроса в одну строку, не длиннее {@link #OPENING_LENGTH} знаков. */
    public String opening() {
        String line = firstPrompt.strip().replaceAll("\\s+", " ");
        if (line.codePointCount(0, line.length()) <= OPENING_LENGTH) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, OPENING_LENGTH)).strip() + "…";
    }
}
