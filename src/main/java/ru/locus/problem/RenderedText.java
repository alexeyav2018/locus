package ru.locus.problem;

/**
 * Отрисовка текстов Задачи для предпросмотра (design.md изменения
 * {@code problem-text}, Р9): PDF в памяти, не положенные никуда.
 *
 * @param condition отрисовка условия; {@code null} — текст условия пуст
 * @param solution  отрисовка решения; {@code null} — текст решения пуст
 */
public record RenderedText(byte[] condition, byte[] solution) {
}
