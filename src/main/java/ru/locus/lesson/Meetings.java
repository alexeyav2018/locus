package ru.locus.lesson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Встречи из правил Занятий и их Поправок — чистая функция без базы
 * (ADR-0047, ADR-0048).
 *
 * <p>Какие даты даёт правило, решает одно место —
 * {@link LessonTiming#occursOn}: у разового одна дата, у еженедельного —
 * каждый его день недели от первой до последней даты включительно. Дата
 * и время местные, без зоны: переход на зимнее время Встреч не сдвигает.
 *
 * <p>Поправка, чью плановую дату правило не даёт, не показывается: она
 * осталась бы висеть после правки, которую сервис ещё не разобрал.
 */
public final class Meetings {

    private static final Comparator<Meeting> ORDER = Comparator.comparing(Meeting::date)
            .thenComparing(Meeting::start)
            .thenComparing(meeting -> meeting.lesson().value());

    private static final Comparator<MovedAway> MOVED_AWAY_ORDER = Comparator.comparing(MovedAway::plannedDate)
            .thenComparing(MovedAway::plannedStart)
            .thenComparing(movedAway -> movedAway.lesson().value());

    private Meetings() {
    }

    /** Встречи Занятий без Поправок — как велит правило. */
    public static List<Meeting> between(List<ListedLesson> lessons, LocalDate from, LocalDate to) {
        return between(lessons, List.of(), from, to);
    }

    /**
     * Встречи Занятий, чья фактическая дата в отрезке включительно, — по дате,
     * затем по времени начала. Плановые даты выводятся по правилу на отрезок
     * и дополняются плановыми датами Встреч, перенесённых в него извне.
     * Отменённая Встреча остаётся на плановой дате, перенесённая — на новом
     * месте. Пересечения во времени не проверяются: две Встречи в одно время
     * обе попадают в ответ.
     */
    public static List<Meeting> between(List<ListedLesson> lessons, Collection<MeetingAdjustment> adjustments,
            LocalDate from, LocalDate to) {
        Map<LessonId, Map<LocalDate, MeetingAdjustment>> byLesson = byLesson(adjustments);
        List<Meeting> meetings = new ArrayList<>();
        for (ListedLesson listed : lessons) {
            LessonTiming timing = listed.lesson().timing();
            Map<LocalDate, MeetingAdjustment> own = byLesson.getOrDefault(listed.lesson().id(), Map.of());
            TreeSet<LocalDate> plannedDates = new TreeSet<>(plannedDates(timing, from, to));
            own.values().stream()
                    .filter(adjustment -> adjustment.move() != null && within(adjustment.move().date(), from, to))
                    .map(MeetingAdjustment::plannedDate)
                    .filter(timing::occursOn)
                    .forEach(plannedDates::add);
            for (LocalDate plannedDate : plannedDates) {
                Meeting meeting = meeting(listed, plannedDate, own.get(plannedDate));
                if (within(meeting.date(), from, to)) {
                    meetings.add(meeting);
                }
            }
        }
        meetings.sort(ORDER);
        return meetings;
    }

    /**
     * Строки «перенесена на …» — перенесённые Встречи, чья плановая дата
     * в отрезке включительно, куда бы их ни перенесли.
     */
    public static List<MovedAway> movedAway(List<ListedLesson> lessons, Collection<MeetingAdjustment> adjustments,
            LocalDate from, LocalDate to) {
        Map<LessonId, Map<LocalDate, MeetingAdjustment>> byLesson = byLesson(adjustments);
        List<MovedAway> movedAway = new ArrayList<>();
        for (ListedLesson listed : lessons) {
            LessonTiming timing = listed.lesson().timing();
            for (MeetingAdjustment adjustment : byLesson.getOrDefault(listed.lesson().id(), Map.of()).values()) {
                if (adjustment.move() != null && within(adjustment.plannedDate(), from, to)
                        && timing.occursOn(adjustment.plannedDate())) {
                    movedAway.add(new MovedAway(listed.lesson().id(), adjustment.plannedDate(), timing.start(),
                            adjustment.move().date(), adjustment.move().start(), listed.addresseeName(),
                            listed.withdrawn()));
                }
            }
        }
        movedAway.sort(MOVED_AWAY_ORDER);
        return movedAway;
    }

    /** Даты отрезка, которые даёт правило. */
    private static List<LocalDate> plannedDates(LessonTiming timing, LocalDate from, LocalDate to) {
        LocalDate first = timing.firstDate().isAfter(from) ? timing.firstDate() : from;
        LocalDate last = timing.lastDate() != null && timing.lastDate().isBefore(to) ? timing.lastDate() : to;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (timing.occursOn(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

    private static Meeting meeting(ListedLesson listed, LocalDate plannedDate, MeetingAdjustment adjustment) {
        LessonTiming timing = listed.lesson().timing();
        LessonId id = listed.lesson().id();
        if (adjustment == null) {
            return new Meeting(id, plannedDate, plannedDate, timing.start(), timing.end(), listed.addresseeName(),
                    listed.withdrawn(), false, false, false);
        }
        MeetingAdjustment.Move move = adjustment.move();
        if (move == null) {
            return new Meeting(id, plannedDate, plannedDate, timing.start(), timing.end(), listed.addresseeName(),
                    listed.withdrawn(), adjustment.cancelled(), false, adjustment.absent());
        }
        return new Meeting(id, plannedDate, move.date(), move.start(), move.end(), listed.addresseeName(),
                listed.withdrawn(), false, true, adjustment.absent());
    }

    private static Map<LessonId, Map<LocalDate, MeetingAdjustment>> byLesson(
            Collection<MeetingAdjustment> adjustments) {
        Map<LessonId, Map<LocalDate, MeetingAdjustment>> byLesson = new HashMap<>();
        for (MeetingAdjustment adjustment : adjustments) {
            byLesson.computeIfAbsent(adjustment.lesson(), lesson -> new HashMap<>())
                    .put(adjustment.plannedDate(), adjustment);
        }
        return byLesson;
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
}
