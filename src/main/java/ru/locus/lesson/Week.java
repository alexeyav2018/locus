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
     * День недели, его Встречи и строки «перенесена на …» Встреч, плановая
     * дата которых — этот день (ADR-0048).
     *
     * @param today     сегодняшний ли это день
     * @param movedAway перенесённые с этого дня Встречи, в порядке планового времени
     */
    public record Day(LocalDate date, boolean today, List<Meeting> meetings, List<MovedAway> movedAway) {

        public Day {
            Objects.requireNonNull(date);
            meetings = List.copyOf(meetings);
            movedAway = List.copyOf(movedAway);
        }

        /** Нет ни Встреч, ни строк о перенесённых. */
        public boolean empty() {
            return meetings.isEmpty() && movedAway.isEmpty();
        }

        /** Название дня недели по-русски с заглавной: «Вторник». */
        public String name() {
            return dayName(date.getDayOfWeek());
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

    /** Имя дня недели с заглавной буквы: «Вторник». */
    public static String dayName(DayOfWeek day) {
        String name = day.getDisplayName(TextStyle.FULL_STANDALONE, RUSSIAN);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Понедельник недели, в которую попадает дата. */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Раскладывает Встречи недели по их фактическим дням, а строки
     * «перенесена на» — по плановым; всё вне недели отбрасывается.
     */
    static Week of(LocalDate monday, LocalDate today, List<Meeting> meetings, List<MovedAway> movedAway) {
        List<Day> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            days.add(day(monday.plusDays(i), today, meetings, movedAway));
        }
        return new Week(monday, today, days);
    }

    /** Один день: Встречи с этой фактической датой и строки «перенесена на» с этой плановой. */
    static Day day(LocalDate date, LocalDate today, List<Meeting> meetings, List<MovedAway> movedAway) {
        return new Day(date, date.equals(today),
                meetings.stream().filter(meeting -> meeting.date().equals(date)).toList(),
                movedAway.stream().filter(away -> away.plannedDate().equals(date)).toList());
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
