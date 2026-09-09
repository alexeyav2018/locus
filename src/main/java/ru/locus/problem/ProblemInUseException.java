package ru.locus.problem;

/**
 * Использованная Задача не правится и не удаляется (ADR-0030).
 *
 * Использованная — вошедшая хотя бы в одно Задание или имеющая хотя бы одну
 * Работу. Сегодня таких не бывает: ни Заданий, ни Работ не существует,
 * и исключение не выбрасывается ни разу — см. {@link ProblemUsage}.
 */
public class ProblemInUseException extends RuntimeException {

    public ProblemInUseException(String message) {
        super(message);
    }
}
