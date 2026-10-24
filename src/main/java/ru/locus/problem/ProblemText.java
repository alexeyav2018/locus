package ru.locus.problem;

import ru.locus.file.FileKey;

/**
 * Текст Задачи с текстом: условие и решение — Markdown с формулами LaTeX —
 * и Улики слотов (ADR-0055; design.md изменения {@code problem-text}, Р2–Р4).
 *
 * <p>Существует только целиком: оба текста или ни одного (Р2), поэтому
 * у Задачи без текста этой записи нет вовсе — {@code Problem.text()}
 * равен {@code null}. Признака «без текста» нигде не заводится: он был бы
 * вторым источником правды рядом с самим текстом и разошёлся бы с ним молча
 * (сторожит {@code TextlessIsNotStoredTest}).
 *
 * <p>Правила состава стоят в компактном конструкторе, как у {@link Problem}:
 * текст в обход правил не должен существовать ни в памяти, ни в базе.
 * Текст хранится как прислан, без отсечения пробелов, — отсечение нужно
 * только чтобы не принять пустой.
 *
 * @param condition          текст условия
 * @param solution           текст решения
 * @param conditionEvidence  Улика условия; {@code null} — Улики нет
 *                           (текст набран рукой)
 * @param solutionEvidence   Улика решения; {@code null} — Улики нет
 * @param version            счётчик правок текста (Р7): правка с версией,
 *                           отличной от хранимой, отклоняется
 */
public record ProblemText(String condition,
                          String solution,
                          FileKey conditionEvidence,
                          FileKey solutionEvidence,
                          int version) {

    /**
     * Предел длины одного текста в знаках. Решение на страницу — около трёх
     * тысяч знаков; предел с запасом в разы отсекает не длинное решение,
     * а случайно вставленный не тот файл.
     */
    public static final int MAX_LENGTH = 20_000;

    public ProblemText {
        if (condition == null && solution == null
                && (conditionEvidence != null || solutionEvidence != null)) {
            throw new IllegalArgumentException("Улика бывает только у Задачи с текстом");
        }
        requireTexts(condition, solution);
        if (version < 0) {
            throw new IllegalArgumentException("Версия текста Задачи не бывает отрицательной");
        }
    }

    /**
     * Проверка обоих текстов, вынесенная отдельно, как
     * {@link Problem#requireMarkup}: ею пользуется {@link ProblemService}
     * до отрисовки, когда записи ещё нет. Одна проверка на оба случая —
     * разойдясь, они дали бы текст, который отрисовывается, но не сохраняется.
     */
    static void requireTexts(String condition, String solution) {
        if (blank(condition) || blank(solution)) {
            throw new IllegalArgumentException(
                    "У Задачи с текстом нужны оба текста — и условия, и решения");
        }
        requireText(ProblemSlot.CONDITION, condition);
        requireText(ProblemSlot.SOLUTION, solution);
    }

    /** Текст одного слота: не пуст после отсечения пробелов и не длиннее предела. */
    static void requireText(ProblemSlot slot, String text) {
        if (blank(text)) {
            throw new IllegalArgumentException("Текст слота «" + slot.title() + "» пуст");
        }
        int length = text.codePointCount(0, text.length());
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("Текст слота «" + slot.title() + "» длиннее "
                    + MAX_LENGTH + " знаков: " + length);
        }
    }

    /** Текст слота. */
    public String of(ProblemSlot slot) {
        return slot == ProblemSlot.CONDITION ? condition : solution;
    }

    /** Улика слота; {@code null} — Улики нет. */
    public FileKey evidenceOf(ProblemSlot slot) {
        return slot == ProblemSlot.CONDITION ? conditionEvidence : solutionEvidence;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
