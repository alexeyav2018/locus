package ru.locus.problem;

/**
 * Задачу разрешено привязывать только к Теме — листу дерева.
 *
 * Инвариант 1 (domain-model.md, ADR-0006): Задачи и отметки Владения несут
 * только Темы. Схемой это не выражается — отдельной таблицы Тем нет и быть
 * не может, вид узла не хранится, а вычисляется по наличию потомков, —
 * поэтому проверка стоит в сервисе, и отказ виден здесь.
 */
public class NotATopicException extends RuntimeException {

    public NotATopicException(String message) {
        super(message);
    }
}
