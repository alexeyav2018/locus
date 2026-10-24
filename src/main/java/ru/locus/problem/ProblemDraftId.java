package ru.locus.problem;

/**
 * Идентификатор Черновика Задачи — отдельный тип, а не голое число.
 *
 * По образцу {@link AssemblyDraftId}: рядом стоят идентификаторы Черновиков
 * сборки и версия, и перепутанное число тихо взяло бы не тот черновик.
 * Черновик принадлежит загрузившему (ADR-0059), и ни один запрос к нему
 * не идёт без владельца.
 */
public record ProblemDraftId(long value) {

    public ProblemDraftId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор Черновика Задачи должен быть положительным: " + value);
        }
    }
}
