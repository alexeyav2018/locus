package ru.locus.problem;

/**
 * Использованная Задача не правится и не удаляется (ADR-0030).
 *
 * Использованная — вошедшая хотя бы в одно Задание или имеющая хотя бы одну
 * Работу. Сегодня выбрасывается по первому основанию: Задания есть, Работ
 * ещё нет — см. {@link ProblemUsage}.
 */
public class ProblemInUseException extends RuntimeException {

    public ProblemInUseException(String message) {
        super(message);
    }
}
