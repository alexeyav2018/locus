package ru.locus.mastery;

import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Ключ ячейки владения — пара «Тема × Метод» (ADR-0011).
 *
 * У отметки нет суррогатного идентификатора: её никто не адресует
 * по номеру, и ключ ячейки и есть её имя. Ученик и владелец в ключ
 * не входят — они задаются вызовом, в котором ячейка живёт
 * ({@code MasteryRepository.findByStudent(UserId, StudentId)}), и одна
 * карта {@code Map<Cell, …>} всегда принадлежит одному Ученику одного
 * Учителя.
 */
public record Cell(TaxonomyNodeId topic, SolutionMethodId method) {

    public Cell {
        if (topic == null) {
            throw new IllegalArgumentException("У ячейки владения должна быть Тема");
        }
        if (method == null) {
            throw new IllegalArgumentException("У ячейки владения должен быть Метод");
        }
    }
}
