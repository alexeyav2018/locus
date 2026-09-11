package ru.locus.taxonomy;

/**
 * Тему, несущую содержимое, нельзя углубить <b>без Темы-приёмника</b>:
 * с появлением потомка она стала бы Разделом, а на Разделе Задач и отметок
 * Владения не бывает (инвариант 1).
 *
 * Отказ называет выход: указать Тему-приёмник (ADR-0007), на которую
 * содержимое переедет той же операцией —
 * {@link TaxonomyService#create(String, TaxonomyNodeId, TopicReceiver)}.
 */
public class TopicCarriesContentException extends RuntimeException {

    public TopicCarriesContentException(String message) {
        super(message);
    }
}
