package ru.locus.lesson;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Встречи у вошедшего Учителя нет: Занятие чужое или не существует, либо
 * его правило на эту плановую дату Встречи не даёт (ADR-0048).
 *
 * Все три случая отвечают одинаково — 404, как {@link LessonNotFoundException}:
 * различие выдало бы существование чужого Занятия.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class MeetingNotFoundException extends RuntimeException {
    public MeetingNotFoundException(LessonId id, LocalDate plannedDate) {
        super("Встречи Занятия " + id.value() + " на " + plannedDate + " нет");
    }
}
