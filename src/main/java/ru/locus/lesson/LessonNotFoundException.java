package ru.locus.lesson;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Занятия с таким идентификатором у вошедшего Учителя нет.
 *
 * Чужое Занятие и несуществующее для сервиса неразличимы: владелец стоит
 * в самом запросе (ADR-0027), и репозиторий отвечает пусто в обоих случаях.
 * Различие выдало бы существование чужого Занятия, поэтому ответ наружу
 * один — 404. Устроено как {@link ru.locus.assignment.AssignmentNotFoundException}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class LessonNotFoundException extends RuntimeException {
    public LessonNotFoundException(LessonId id) {
        super("Занятия с идентификатором " + id.value() + " не существует");
    }
}
