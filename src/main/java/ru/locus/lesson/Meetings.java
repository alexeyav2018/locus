package ru.locus.lesson;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Встречи из правил Занятий — чистая функция без базы (ADR-0047).
 *
 * <p>У разового Занятия одна Встреча — в дату первой встречи, если она
 * в отрезке. У еженедельного — каждая дата его дня недели от
 * {@code max(from, firstDate)} до {@code min(to, lastDate)}; без последней
 * даты — до конца отрезка. Дата и время местные, без зоны: переход
 * на зимнее время Встреч не сдвигает.
 */
public final class Meetings {

    private static final Comparator<Meeting> ORDER = Comparator.comparing(Meeting::date)
            .thenComparing(Meeting::start)
            .thenComparing(meeting -> meeting.lesson().value());

    private Meetings() {
    }

    /**
     * Встречи Занятий в отрезке дат включительно — по дате, затем по времени
     * начала. Пересечения во времени не проверяются: две Встречи в одно время
     * обе попадают в ответ.
     */
    public static List<Meeting> between(List<ListedLesson> lessons, LocalDate from, LocalDate to) {
        List<Meeting> meetings = new ArrayList<>();
        for (ListedLesson listed : lessons) {
            LessonTiming timing = listed.lesson().timing();
            if (!timing.weekly()) {
                if (!timing.firstDate().isBefore(from) && !timing.firstDate().isAfter(to)) {
                    meetings.add(meeting(listed, timing.firstDate()));
                }
                continue;
            }
            LocalDate last = timing.lastDate() == null || timing.lastDate().isAfter(to) ? to : timing.lastDate();
            LocalDate date = timing.firstDate().isBefore(from)
                    ? from.with(TemporalAdjusters.nextOrSame(timing.dayOfWeek()))
                    : timing.firstDate();
            for (; !date.isAfter(last); date = date.plusWeeks(1)) {
                meetings.add(meeting(listed, date));
            }
        }
        meetings.sort(ORDER);
        return meetings;
    }

    private static Meeting meeting(ListedLesson listed, LocalDate date) {
        LessonTiming timing = listed.lesson().timing();
        return new Meeting(listed.lesson().id(), date, timing.start(), timing.end(), listed.addresseeName(),
                listed.withdrawn());
    }
}
