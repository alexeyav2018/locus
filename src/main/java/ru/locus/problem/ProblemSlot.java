package ru.locus.problem;

/**
 * Слот Задачи — условие или решение (glossary.md, «Задача»).
 *
 * У каждого слота свои PDF, текст и Улика, и правка текста идёт по слоту:
 * правится решение — условие остаётся байт в байт (design.md изменения
 * {@code problem-text}, Р8, Р10). Перечисление, а не булев флаг
 * «это условие», — чтобы вызов читался словом, а не {@code true}.
 */
public enum ProblemSlot {

    CONDITION("условие"),

    SOLUTION("решение");

    private final String title;

    ProblemSlot(String title) {
        this.title = title;
    }

    /** Имя слота по-русски — для показа и для имени файла {@code .md}. */
    public String title() {
        return title;
    }
}
