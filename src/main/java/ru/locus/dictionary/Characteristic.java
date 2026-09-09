package ru.locus.dictionary;

/**
 * Характеристика — уточняющий признак задачи.
 *
 * Устроена так же, как {@link SolutionMethod}, и ведётся так же, но
 * в измерении владения <b>не участвует</b>: она уточняет только поиск
 * (ADR-0009). Поэтому и запись отдельная, а не общая с признаком вида
 * словаря: сведённые в одну, они разошлись бы обратно при первой же разметке,
 * но уже с лишним ключом, который пришлось бы проверять в каждом запросе.
 */
public record Characteristic(CharacteristicId id, String name) {

    public Characteristic {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя Характеристики не может быть пустым");
        }
    }
}
