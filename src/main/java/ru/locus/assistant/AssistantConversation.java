package ru.locus.assistant;

import java.time.Instant;
import ru.locus.user.UserId;

/**
 * Разговор — последовательность Реплик одного учителя с Помощником (ADR-0054).
 *
 * <p>Историю для модели держит Claude Code, Локус — идентификатор его сеанса:
 * следующая Реплика того же Разговора возобновляет сеанс. Пока первая
 * Реплика не получила сеанс от процесса, он пуст.
 *
 * @param session идентификатор сеанса Claude Code; {@code null}, пока его нет
 */
public record AssistantConversation(AssistantConversationId id, UserId owner, String session, Instant createdAt) {

    /** Сеанс уже есть, и следующую Реплику нужно запускать с его возобновлением. */
    public boolean hasSession() {
        return session != null;
    }
}
