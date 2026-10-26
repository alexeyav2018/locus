package ru.locus.warmup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Разминки с таким идентификатором у вошедшего Учителя нет.
 *
 * Чужая Разминка и несуществующая для сервиса неразличимы: репозиторий
 * отвечает пусто в обоих случаях, потому что владелец стоит в самом запросе
 * (ADR-0027). Различие выдало бы существование чужой Разминки, поэтому
 * и ответ наружу один — 404. Устроено как
 * {@link ru.locus.assignment.AssignmentNotFoundException}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class WarmupNotFoundException extends RuntimeException {
    public WarmupNotFoundException(WarmupId id) {
        super("Разминки с идентификатором " + id.value() + " не существует");
    }
}
