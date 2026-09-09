package ru.locus.taxonomy;

/**
 * Тему, несущую содержимое, нельзя углубить: с появлением потомка она стала бы
 * Разделом, а на Разделе Задач и отметок Владения не бывает (инвариант 1).
 *
 * Отказ временный и назван прямо: перенос содержимого на Тему-приёмник
 * (ADR-0007) принадлежит работе {@code rubricator-restructure}. До неё
 * углубить такую Тему нельзя, и Администратору сообщается почему.
 */
public class TopicCarriesContentException extends RuntimeException {

    public TopicCarriesContentException(String message) {
        super(message);
    }
}
