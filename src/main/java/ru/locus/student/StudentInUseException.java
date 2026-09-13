package ru.locus.student;

/**
 * Ученик, на которого что-то ссылается, не удаляется (ADR-0035).
 *
 * Ссылаться могут Задание, Работа и отметка Владения. Сегодня выбрасывается
 * по первому основанию: Задания есть, Работ и отметок ещё нет —
 * см. {@link StudentUsage}.
 */
public class StudentInUseException extends RuntimeException {

    public StudentInUseException(String message) {
        super(message);
    }
}
