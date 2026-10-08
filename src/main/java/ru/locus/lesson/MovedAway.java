package ru.locus.lesson;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Строка «перенесена на …» на плановой дате перенесённой Встречи
 * (ADR-0048): показывает, куда делась привычная Встреча. Сама Встреча
 * показывается на новом месте как {@link Meeting} с признаком переноса.
 *
 * @param plannedDate  дата Встречи по правилу
 * @param plannedStart время начала по правилу
 * @param date         новая дата
 * @param start        новое время начала
 */
public record MovedAway(LessonId lesson, LocalDate plannedDate, LocalTime plannedStart, LocalDate date,
        LocalTime start, String addresseeName, boolean withdrawn) {

    public MovedAway {
        Objects.requireNonNull(lesson);
        Objects.requireNonNull(plannedDate);
        Objects.requireNonNull(plannedStart);
        Objects.requireNonNull(date);
        Objects.requireNonNull(start);
    }
}
