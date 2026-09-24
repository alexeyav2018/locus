package ru.locus.mastery;

import ru.locus.dictionary.SolutionMethod;

/**
 * Строка таблицы «По Методу» на экране Владения — Метод и его
 * распределение по всем Темам, где он среди ячеек (`mastery-views`).
 *
 * В таблицу попадают только Методы с {@code distribution.cells() > 0}:
 * Метод без единой ячейки не «неизвестен», у него нечего распределять
 * (design.md).
 */
public record MasteryOfMethodRow(SolutionMethod method, Distribution distribution) {

    public MasteryOfMethodRow {
        if (method == null) {
            throw new IllegalArgumentException("У строки владения по Методу должен быть Метод");
        }
        if (distribution == null) {
            throw new IllegalArgumentException("У строки владения по Методу должно быть распределение");
        }
    }
}
