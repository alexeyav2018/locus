package ru.locus.mastery;

import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Одна простановка с формы ячеек: ячейка и новое значение.
 *
 * <p>Форма шлёт по строке на каждую ячейку Задачи, и у большинства
 * из них выбрано «без изменения» — это {@code status == null}, и такую
 * строку сервис пропускает. {@code null} здесь — не «неизвестно»:
 * «неизвестно» — это {@link MasteryStatus#UNKNOWN}, и его простановка
 * <b>снимает</b> отметку (ADR-0039). Различие важно: перепутав их, форма
 * стирала бы все отметки Задачи при каждом сохранении.
 *
 * @param status новое значение; {@code null} — ячейку не трогать
 */
public record Mark(TaxonomyNodeId topic, SolutionMethodId method, MasteryStatus status) {

    public Mark {
        if (topic == null) {
            throw new IllegalArgumentException("У отметки должна быть Тема");
        }
        if (method == null) {
            throw new IllegalArgumentException("У отметки должен быть Метод");
        }
    }

    /** Ключ ячейки, к которой относится простановка. */
    public Cell cell() {
        return new Cell(topic, method);
    }
}
