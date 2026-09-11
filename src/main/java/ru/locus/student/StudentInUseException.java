package ru.locus.student;

/**
 * Ученик, на которого что-то ссылается, не удаляется (ADR-0035).
 *
 * Ссылаться могут Задание, Работа и отметка Владения. Сегодня их не бывает:
 * ни одной из трёх сущностей не существует, и исключение не выбрасывается
 * ни разу — см. {@link StudentUsage}.
 */
public class StudentInUseException extends RuntimeException {

    public StudentInUseException(String message) {
        super(message);
    }
}
