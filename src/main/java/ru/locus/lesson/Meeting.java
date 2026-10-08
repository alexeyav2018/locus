package ru.locus.lesson;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Встреча — вычисленный случай Занятия в конкретную дату (ADR-0047).
 * Не хранится: её порождает {@link Meetings} из правила при каждом показе.
 *
 * @param lesson        Занятие, из правила которого Встреча выведена
 * @param date          дата Встречи
 * @param start         время начала
 * @param end           время окончания
 * @param addresseeName имя Ученика или Группы
 * @param withdrawn     Ученик Занятия выбыл (ADR-0040); у Группы всегда ложно
 */
public record Meeting(LessonId lesson, LocalDate date, LocalTime start, LocalTime end, String addresseeName,
        boolean withdrawn) {

    public Meeting {
        Objects.requireNonNull(lesson);
        Objects.requireNonNull(date);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
    }
}
