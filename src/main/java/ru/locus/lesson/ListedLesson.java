package ru.locus.lesson;

/**
 * Занятие вместе с тем, что о его адресате нужно для показа: имя Ученика
 * или Группы и признак, что Ученик выбыл (ADR-0040, ADR-0047). У Группы
 * признак всегда ложен — Занятие Группы идёт с Группой, а не с её членами.
 */
public record ListedLesson(Lesson lesson, String addresseeName, boolean withdrawn) {
}
