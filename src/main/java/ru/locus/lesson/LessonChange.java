package ru.locus.lesson;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Правка Занятия с его карточки — новые значения, ещё не проверенные.
 *
 * <p>Разовое Занятие берёт {@code date}, время и длительность и правится
 * на месте. Еженедельное берёт день недели, время, длительность и последнюю
 * дату; дата, с которой правка действует, передаётся отдельно, и по ней
 * {@link LessonService#change} решает, делить ли Занятие (ADR-0047).
 * Адресата и вида Занятия правка не меняет.
 *
 * @param date      дата разового Занятия; у еженедельного не читается
 * @param dayOfWeek день недели еженедельного Занятия; у разового не читается
 * @param lastDate  последняя встреча еженедельного; {@code null} — бессрочно
 */
public record LessonChange(LocalDate date, DayOfWeek dayOfWeek, LocalTime start, Integer durationMinutes,
        LocalDate lastDate) {
}
