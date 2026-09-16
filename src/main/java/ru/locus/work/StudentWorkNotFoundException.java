package ru.locus.work;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Работы с таким идентификатором у вошедшего Учителя нет.
 *
 * Чужая Работа и несуществующая для сервиса неразличимы: репозиторий
 * отвечает пусто в обоих случаях, потому что владелец стоит в самом
 * запросе (ADR-0027). Различие выдало бы существование чужой Работы,
 * поэтому и ответ наружу один — 404 (спека, «Правка чужой Работы»).
 * Устроено как {@link ru.locus.assignment.AssignmentNotFoundException}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class StudentWorkNotFoundException extends RuntimeException {
    public StudentWorkNotFoundException(StudentWorkId id) {
        super("Работы с идентификатором " + id.value() + " не существует");
    }
}
