package ru.locus.lesson;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

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
        if (firstDate == null) {
            throw new IllegalArgumentException("Дата первой встречи обязательна");
        }
        if (start == null) {
            throw new IllegalArgumentException("Время начала обязательно");
        }
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

    /**
     * Правило из полей формы: незаполненная длительность — такой же отказ,
     * как недопустимая, а не ошибка разбора.
     */
    public static LessonTiming of(LocalDate firstDate, LocalDate lastDate, boolean weekly, LocalTime start,
            Integer durationMinutes) {
        if (durationMinutes == null) {
            throw new IllegalArgumentException("Длительность Занятия обязательна");
        }
        return new LessonTiming(firstDate, lastDate, weekly, start, durationMinutes);
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

    /**
     * Даёт ли правило Встречу в эту дату: у разового — только в дату первой
     * встречи, у еженедельного — в каждый его день недели от первой даты
     * до последней включительно. По этому вопросу снимаются Поправки, которых
     * правило после правки больше не даёт (ADR-0048).
     */
    public boolean occursOn(LocalDate date) {
        if (date.isBefore(firstDate)) {
            return false;
        }
        if (!weekly) {
            return date.equals(firstDate);
        }
        return date.getDayOfWeek() == dayOfWeek() && (lastDate == null || !date.isAfter(lastDate));
    }
}
