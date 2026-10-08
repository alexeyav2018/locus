package ru.locus.lesson;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Поправка Встречи — то, чем реальная Встреча отличается от правила
 * Занятия, на паре «Занятие × плановая дата» (ADR-0048): отмена, перенос
 * или неявка. Плановая дата — та, что Встрече даёт правило; она остаётся
 * ключом Встречи, сколько бы раз её ни переносили.
 *
 * <p>Отмена исключает перенос и неявку. «Как по правилу» — отсутствие
 * строки: поправка, в которой ничего нет ({@link #isEmpty()}), не хранится,
 * сервис её удаляет. Те же правила стоят проверками таблицы
 * {@code meeting_adjustment} (миграция {@code 0013-schedule-changes.yaml}).
 * Что неявка бывает только у Занятия с Учеником, держит сервис: запись
 * адресата Занятия не знает.
 *
 * @param move    перенос; {@code null} — Встреча на плановом месте
 * @param absent  Ученик не пришёл
 */
public record MeetingAdjustment(LessonId lesson, LocalDate plannedDate, boolean cancelled, Move move,
        boolean absent) {

    /**
     * Новое место Встречи: дата, время начала и длительность.
     */
    public record Move(LocalDate date, LocalTime start, int durationMinutes) {

        public Move {
            if (date == null) {
                throw new IllegalArgumentException("Дата переноса обязательна");
            }
            if (start == null) {
                throw new IllegalArgumentException("Время начала переноса обязательно");
            }
            if (durationMinutes < 1 || durationMinutes > LessonTiming.MAX_DURATION_MINUTES) {
                throw new IllegalArgumentException("Длительность Встречи — от 1 до "
                        + LessonTiming.MAX_DURATION_MINUTES + " минут: " + durationMinutes);
            }
        }

        /** Перенос из полей формы: незаполненная длительность — такой же отказ, как недопустимая. */
        public static Move of(LocalDate date, LocalTime start, Integer durationMinutes) {
            if (durationMinutes == null) {
                throw new IllegalArgumentException("Длительность Встречи обязательна");
            }
            return new Move(date, start, durationMinutes);
        }

        public LocalTime end() {
            return start.plusMinutes(durationMinutes);
        }
    }

    public MeetingAdjustment {
        Objects.requireNonNull(lesson);
        Objects.requireNonNull(plannedDate);
        if (cancelled && move != null) {
            throw new IllegalArgumentException("Отменённую Встречу нельзя одновременно перенести");
        }
        if (cancelled && absent) {
            throw new IllegalArgumentException("У отменённой Встречи не бывает неявки");
        }
    }

    /** Встреча по правилу, без поправки — с неё начинается любое действие. */
    public static MeetingAdjustment none(LessonId lesson, LocalDate plannedDate) {
        return new MeetingAdjustment(lesson, plannedDate, false, null, false);
    }

    /** В поправке ничего нет: Встреча идёт по правилу, хранить нечего. */
    public boolean isEmpty() {
        return !cancelled && move == null && !absent;
    }

    public MeetingAdjustment withCancelled(boolean cancelled) {
        return new MeetingAdjustment(lesson, plannedDate, cancelled, cancelled ? null : move, absent);
    }

    public MeetingAdjustment withMove(Move move) {
        return new MeetingAdjustment(lesson, plannedDate, move == null && cancelled, move, absent);
    }

    public MeetingAdjustment withAbsent(boolean absent) {
        return new MeetingAdjustment(lesson, plannedDate, cancelled, move, absent);
    }

    /** Та же поправка у другой части поделённого Занятия (ADR-0048). */
    public MeetingAdjustment forLesson(LessonId other) {
        return new MeetingAdjustment(other, plannedDate, cancelled, move, absent);
    }
}
