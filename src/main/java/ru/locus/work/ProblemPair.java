package ru.locus.work;

import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Пара «Тема × Метод» из разметки Задачи — ключ справки «решено N из M»
 * ({@link StudentWorkRepository#countCheckedByPairs}).
 *
 * Своя запись, а не {@code mastery.Cell}, хотя устроены они одинаково:
 * {@code work} не зависит от {@code mastery}. Зависимость идёт в одну
 * сторону — область отметок читает Работы для справки, а Работы об отметках
 * не знают ничего (design.md, «Область mastery без собственного экрана»).
 * Одна запись на обе области замкнула бы кольцо пакетов ради экономии
 * четырёх строк.
 */
public record ProblemPair(TaxonomyNodeId topic, SolutionMethodId method) {

    public ProblemPair {
        if (topic == null) {
            throw new IllegalArgumentException("У пары должна быть Тема");
        }
        if (method == null) {
            throw new IllegalArgumentException("У пары должен быть Метод");
        }
    }
}
