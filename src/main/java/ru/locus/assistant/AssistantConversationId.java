package ru.locus.assistant;

/**
 * Идентификатор Разговора — отдельный тип, а не голое число: рядом с ним
 * в вызовах репозитория стоят владелец и идентификатор Реплики, и перепутанные
 * местами числа тихо читали бы не ту запись.
 */
public record AssistantConversationId(long value) {
    public AssistantConversationId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор Разговора должен быть положительным: " + value);
        }
    }
}
