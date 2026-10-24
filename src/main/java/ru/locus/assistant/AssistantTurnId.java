package ru.locus.assistant;

/** Идентификатор Реплики — отдельный тип, по образцу {@link AssistantConversationId}. */
public record AssistantTurnId(long value) {
    public AssistantTurnId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор Реплики должен быть положительным: " + value);
        }
    }
}
