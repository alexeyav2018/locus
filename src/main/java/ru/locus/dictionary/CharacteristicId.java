package ru.locus.dictionary;

/**
 * Идентификатор Характеристики — отдельный тип, а не голое число и не общий
 * тип с {@link SolutionMethodId}.
 *
 * Общий идентификатор на оба словаря пропустил бы Характеристику туда, где
 * ждут Метод, — а Метод участвует в измерении владения, Характеристика нет
 * (ADR-0009). Ошибка была бы тихой: числа одинаковые, записи одинаковые
 * по составу.
 */
public record CharacteristicId(long value) {

    public CharacteristicId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор Характеристики должен быть положительным: " + value);
        }
    }
}
