package ru.locus.mastery;

import java.util.Collection;

/**
 * Распределение ячеек владения по четырём статусам — единственный агрегат,
 * который система знает (glossary.md, «Имена»: `Distribution` закреплено
 * именно за этим смыслом). Единого «значения владения» — доли, балла,
 * одного статуса на Тему, Раздел или Метод — нет и не будет
 * (ADR-0013): агрегация возможна только сложением по четырём числам,
 * и {@link NoSingleMasteryValueTest} сторожит, что метода, возвращающего
 * такое значение, здесь никогда не появится.
 */
public record Distribution(int mastered, int uncertain, int notMastered, int unknown) {

    public Distribution {
        if (mastered < 0 || uncertain < 0 || notMastered < 0 || unknown < 0) {
            throw new IllegalArgumentException("Число ячеек не может быть отрицательным");
        }
    }

    /** Пустое распределение — «ячеек нет» у узла без единой ячейки в поддереве. */
    public static Distribution empty() {
        return new Distribution(0, 0, 0, 0);
    }

    /**
     * Распределение по переданным статусам ячеек. Ячейка без суждения
     * в переданную коллекцию не входит вовсе — счёт `unknown` собирает
     * вызывающий код (`MasteryService`) из ячеек без строки в карте
     * суждений; здесь считаются только переданные статусы один в один.
     */
    public static Distribution of(Collection<MasteryStatus> statuses) {
        int mastered = 0;
        int uncertain = 0;
        int notMastered = 0;
        int unknown = 0;
        for (MasteryStatus status : statuses) {
            switch (status) {
                case MASTERED -> mastered++;
                case UNCERTAIN -> uncertain++;
                case NOT_MASTERED -> notMastered++;
                case UNKNOWN -> unknown++;
            }
        }
        return new Distribution(mastered, uncertain, notMastered, unknown);
    }

    /** Сумма двух распределений — Раздела по поддереву, Метода по его Темам. */
    public Distribution plus(Distribution other) {
        return new Distribution(
                mastered + other.mastered,
                uncertain + other.uncertain,
                notMastered + other.notMastered,
                unknown + other.unknown);
    }

    /** Сколько ячеек всего — 0 означает «ячеек нет». */
    public int cells() {
        return mastered + uncertain + notMastered + unknown;
    }
}
