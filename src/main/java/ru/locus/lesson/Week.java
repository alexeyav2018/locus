package ru.locus.lesson;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Неделя расписания с понедельника по воскресенье: по каждому дню — его
 * Встречи в порядке времени. Пустой день остаётся в неделе с пустым
 * списком, чтобы экран мог его подписать.
 *
 * @param monday понедельник недели
 * @param today  сегодняшняя дата по часам приложения — по ней выделяется день
 *               и решается, текущая ли это неделя
 * @param days   семь дней по порядку
 */
public record Week(LocalDate monday, LocalDate today, List<Day> days) {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    /**
     * День недели и его Встречи.
     *
     * @param today сегодняшний ли это день
     */
    public record Day(LocalDate date, boolean today, List<Meeting> meetings) {

        public Day {
            Objects.requireNonNull(date);
            meetings = List.copyOf(meetings);
        }

        /** Название дня недели по-русски с заглавной: «Вторник». */
        public String name() {
            String name = date.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, RUSSIAN);
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    public Week {
        Objects.requireNonNull(monday);
        Objects.requireNonNull(today);
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("Неделя начинается с понедельника: " + monday);
        }
        days = List.copyOf(days);
    }

    /** Понедельник недели, в которую попадает дата. */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Раскладывает Встречи недели по её дням; Встречи вне недели отбрасываются. */
    static Week of(LocalDate monday, LocalDate today, List<Meeting> meetings) {
        List<Day> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            days.add(new Day(date, date.equals(today),
                    meetings.stream().filter(meeting -> meeting.date().equals(date)).toList()));
        }
        return new Week(monday, today, days);
    }

    public LocalDate sunday() {
        return monday.plusDays(6);
    }

    public LocalDate previous() {
        return monday.minusWeeks(1);
    }

    public LocalDate next() {
        return monday.plusWeeks(1);
    }

    /** Содержит ли неделя сегодняшний день. */
    public boolean current() {
        return !today.isBefore(monday) && !today.isAfter(sunday());
    }
}
