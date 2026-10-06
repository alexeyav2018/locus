package ru.locus.problem;

/**
 * Идентификатор черновика сборки — отдельный тип, а не голое число.
 *
 * По образцу {@link ru.locus.work.StudentWorkId}: рядом с ним в строке
 * сборки стоят номера страниц «с» и «по», и перепутанное местами число
 * тихо взяло бы не тот черновик. Черновик принадлежит загрузившему
 * (ADR-0041), и ни один запрос к нему не идёт без владельца.
 */
public record AssemblyDraftId(long value) {

    public AssemblyDraftId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор черновика сборки должен быть положительным: " + value);
        }
    }
}
