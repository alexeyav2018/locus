package ru.locus.lesson;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Правило во времени, по которому Занятие порождает Встречи (ADR-0047):
 * дата первой встречи, время начала, длительность и вид — разовое или
 * еженедельное. День недели еженедельного Занятия — день недели первой
 * встречи; последняя дата бывает только у еженедельного и не раньше первой,
 * без неё оно идёт бессрочно.
 *
 * <p>Те же правила стоят проверочными ограничениями в таблице {@code lesson}
 * (миграция {@code 0012-schedule.yaml}): здесь они дают понятный отказ,
 * там — держат данные от записи в обход кода.
 *
 * @param lastDate последняя встреча еженедельного Занятия; {@code null} — бессрочно
 *                 или разовое
 */
public record LessonTiming(LocalDate firstDate, LocalDate lastDate, boolean weekly, LocalTime start,
        int durationMinutes) {

    /** Наибольшая длительность Встречи — двенадцать часов. */
    public static final int MAX_DURATION_MINUTES = 720;

    public LessonTiming {
        Objects.requireNonNull(firstDate, "Дата первой встречи обязательна");
        Objects.requireNonNull(start, "Время начала обязательно");
        if (durationMinutes < 1 || durationMinutes > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                    "Длительность Занятия — от 1 до " + MAX_DURATION_MINUTES + " минут: " + durationMinutes);
        }
        if (lastDate != null && !weekly) {
            throw new IllegalArgumentException("Последняя дата бывает только у еженедельного Занятия");
        }
        if (lastDate != null && lastDate.isBefore(firstDate)) {
            throw new IllegalArgumentException("Последняя дата Занятия раньше первой встречи");
        }
    }

    /** Разовое Занятие на дату. */
    public static LessonTiming once(LocalDate date, LocalTime start, int durationMinutes) {
        return new LessonTiming(date, null, false, start, durationMinutes);
    }

    /** Еженедельное Занятие с первой встречи до последней включительно; {@code lastDate == null} — бессрочно. */
    public static LessonTiming weekly(LocalDate firstDate, LocalDate lastDate, LocalTime start, int durationMinutes) {
        return new LessonTiming(firstDate, lastDate, true, start, durationMinutes);
    }

    /** День недели Встреч — день недели первой встречи. */
    public DayOfWeek dayOfWeek() {
        return firstDate.getDayOfWeek();
    }

    /** Время окончания Встречи. */
    public LocalTime end() {
        return start.plusMinutes(durationMinutes);
    }
}
