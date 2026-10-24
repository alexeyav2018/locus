package ru.locus.assistant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Реплики с таким идентификатором у вошедшего учителя нет. Чужая Реплика
 * неотличима от несуществующей — её не прочитать и не остановить (ADR-0027).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AssistantTurnNotFoundException extends RuntimeException {

    public AssistantTurnNotFoundException(AssistantTurnId id) {
        super("Реплики с идентификатором " + id.value() + " не существует");
    }
}
