package ru.locus.dictionary;

/**
 * Идентификатор Метода — отдельный тип, а не голое число.
 *
 * По образцу {@link ru.locus.taxonomy.TaxonomyNodeId}: словарей два, они
 * структурно неразличимы, и перепутанные местами числа не роняют запрос,
 * а тихо правят запись не того словаря. Разные типы у
 * {@code SolutionMethodId} и {@link CharacteristicId} делают такую
 * перестановку ошибкой компиляции.
 *
 * Словарь — общая библиотека, под Администратором; владельца у записи нет
 * и быть не должно (ADR-0027).
 */
public record SolutionMethodId(long value) {

    public SolutionMethodId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор Метода должен быть положительным: " + value);
        }
    }
}
