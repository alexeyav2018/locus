package ru.locus.lesson;

/**
 * Встреча для её страницы: сама Встреча с Поправкой и Занятие с адресатом,
 * из правила которого она выведена (ADR-0048).
 *
 * @param started фактическая дата Встречи не позже сегодняшней — неявку
 *                можно отметить только у наступившей
 */
public record MeetingDetails(ListedLesson listed, Meeting meeting, boolean started) {

    /** Неявка отмечается только у Встречи Занятия с Учеником (ADR-0048). */
    public boolean withStudent() {
        return listed.lesson().student() != null;
    }
}
