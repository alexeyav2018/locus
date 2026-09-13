package ru.locus.assignment;

/**
 * Задание, по которому уже есть Работа, не удаляется (ADR-0037).
 *
 * Сегодня не выбрасывается ни разу: Работ в системе не существует,
 * и вопрос {@link AssignmentWork} остаётся без ответа — см. его пояснение.
 * Раздача отклоняется целиком, если хотя бы одно её Задание удержано.
 */
public class AssignmentInUseException extends RuntimeException {
    public AssignmentInUseException(String message) {
        super(message);
    }
}
