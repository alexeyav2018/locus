package ru.locus.student;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Группы с таким идентификатором у вошедшего Учителя нет.
 *
 * Чужая Группа и несуществующая для сервиса неразличимы — по той же
 * причине, что у {@link StudentNotFoundException}: владелец стоит в самом
 * запросе репозитория (ADR-0027), и пусто приходит в обоих случаях.
 * Различие выдало бы существование чужой Группы, поэтому наружу уходит
 * один ответ — 404 (спека, «Правка чужой Группы»).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class GroupNotFoundException extends RuntimeException {

    public GroupNotFoundException(GroupId id) {
        super("Группы с идентификатором " + id.value() + " не существует");
    }
}
