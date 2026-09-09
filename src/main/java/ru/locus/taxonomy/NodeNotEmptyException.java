package ru.locus.taxonomy;

/**
 * Удалять разрешено только пустой узел.
 *
 * Пустой — значит не несущий ни потомков, ни собственного содержимого.
 * Удаление поддерева одним действием не предусмотрено: узлы снимаются
 * по одному, снизу вверх, и каждый шаг виден (ADR-0007).
 */
public class NodeNotEmptyException extends RuntimeException {

    public NodeNotEmptyException(String message) {
        super(message);
    }
}
