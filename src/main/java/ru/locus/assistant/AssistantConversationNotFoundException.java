package ru.locus.assistant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Разговора с таким идентификатором у вошедшего учителя нет.
 *
 * <p>Чужой Разговор и несуществующий неразличимы: владелец стоит в самом
 * запросе (ADR-0027), и продолжить чужой Разговор — значит получить этот
 * отказ, а не чужой сеанс. Устроено как {@link ru.locus.lesson.LessonNotFoundException}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AssistantConversationNotFoundException extends RuntimeException {

    public AssistantConversationNotFoundException(AssistantConversationId id) {
        super("Разговора с идентификатором " + id.value() + " не существует");
    }
}
