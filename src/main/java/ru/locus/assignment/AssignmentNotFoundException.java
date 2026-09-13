package ru.locus.assignment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Задания с таким идентификатором у вошедшего Учителя нет.
 *
 * Чужое Задание и несуществующее для сервиса неразличимы: репозиторий
 * отвечает пусто в обоих случаях, потому что владелец стоит в самом запросе
 * (ADR-0027). Различие выдало бы существование чужого Задания, поэтому
 * и ответ наружу один — 404 (спека, «Прямой адрес чужого Задания
 * и чужой Раздачи»). Устроено как {@link ru.locus.student.StudentNotFoundException}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AssignmentNotFoundException extends RuntimeException {
    public AssignmentNotFoundException(AssignmentId id) {
        super("Задания с идентификатором " + id.value() + " не существует");
    }
}
