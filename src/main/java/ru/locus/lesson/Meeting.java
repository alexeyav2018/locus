package ru.locus.lesson;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Встреча — вычисленный случай Занятия в конкретную дату (ADR-0047)
 * с учётом его Поправки (ADR-0048). Не хранится: её порождает
 * {@link Meetings} из правила и Поправок при каждом показе.
 *
 * <p>Ключ Встречи — Занятие и плановая дата, которую даёт правило; дата
 * и время — уже фактические, после переноса. Отменённая Встреча остаётся
 * на плановой дате с признаком {@code cancelled}: неделя должна отличать
 * «отменили» от «и не было».
 *
 * @param lesson        Занятие, из правила которого Встреча выведена
 * @param plannedDate   дата Встречи по правилу — её ключ
 * @param date          фактическая дата Встречи
 * @param start         время начала
 * @param end           время окончания
 * @param addresseeName имя Ученика или Группы
 * @param withdrawn     Ученик Занятия выбыл (ADR-0040); у Группы всегда ложно
 * @param cancelled     Встреча отменена
 * @param moved         Встреча перенесена с плановой даты и времени
 * @param absent        Ученик не пришёл
 */
public record Meeting(LessonId lesson, LocalDate plannedDate, LocalDate date, LocalTime start, LocalTime end,
        String addresseeName, boolean withdrawn, boolean cancelled, boolean moved, boolean absent) {

    public Meeting {
        Objects.requireNonNull(lesson);
        Objects.requireNonNull(plannedDate);
        Objects.requireNonNull(date);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
    }
}
