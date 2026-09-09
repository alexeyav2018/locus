package ru.locus.taxonomy;

/**
 * Узел не может переехать ни под себя, ни под собственного потомка.
 *
 * Такая операция отрезала бы ветку от корня: узлы продолжили бы существовать,
 * но перестали бы находиться обходом от корня. Потеря была бы тихой — ни
 * ошибки, ни пустого места на экране, просто ветки больше нет.
 */
public class MoveIntoOwnSubtreeException extends RuntimeException {

    public MoveIntoOwnSubtreeException(String message) {
        super(message);
    }
}
