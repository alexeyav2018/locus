package ru.locus.mastery;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Отметки из формы ячеек: три параллельных списка {@code topic},
     * {@code method}, {@code status}, по элементу на ячейку, собираются
     * по индексу. Пустой {@code status} — «без изменения». Списки
     * контроллер читает сырыми из запроса, а не через
     * {@code @RequestParam List<String>}: конвертер Spring разбирает
     * единственное значение как список через запятую и пустую строку
     * превращает в пустой список — у Задачи с одной ячейкой «без изменения»
     * списки разошлись бы по длине, и форма ломалась бы молча.
     *
     * @param topics   значения {@code topic}; {@code null} — полей нет
     * @param methods  значения {@code method}; {@code null} — полей нет
     * @param statuses значения {@code status}; {@code null} — полей нет
     * @throws IllegalArgumentException разошедшиеся длины — испорченная
     *         форма; неизвестное значение шкалы отвергает
     *         {@link MasteryStatus#valueOf}
     */
    public static List<Mark> fromForm(String[] topics, String[] methods, String[] statuses) {
        String[] t = topics == null ? new String[0] : topics;
        String[] m = methods == null ? new String[0] : methods;
        String[] v = statuses == null ? new String[0] : statuses;
        if (t.length != m.length || t.length != v.length) {
            throw new IllegalArgumentException("Форма ячеек повреждена: Темы, Методы и значения не сходятся по числу");
        }
        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            MasteryStatus status = v[i].isBlank() ? null : MasteryStatus.valueOf(v[i]);
            marks.add(new Mark(new TaxonomyNodeId(Long.parseLong(t[i])),
                               new SolutionMethodId(Long.parseLong(m[i])),
                               status));
        }
        return List.copyOf(marks);
    }

    /** Ключ ячейки, к которой относится простановка. */
    public Cell cell() {
        return new Cell(topic, method);
    }
}
