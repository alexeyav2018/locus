package ru.locus.problem;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Что отмечено в форме разметки Задачи — один источник для всех полей.
 *
 * Форма показывается в трёх положениях: пустая с Темой, с которой пришли;
 * заполненная сохранённой Задачей; и после отказа сервиса — заполненная
 * присланным. Шаблон отмечает поля только по этой записи и не выбирает
 * источник сам: выбирай он в каждом поле между Задачей, Темой и запросом,
 * поля разъехались бы молча (design.md, «Объект формы»).
 *
 * Идентификаторы — числами, как их шлёт и сравнивает форма; порядок Тем
 * сохраняется, потому что подсказку Методов строит первая.
 *
 * @param part            Часть ЕГЭ; {@code null} — не выбрана
 * @param topics          отмеченные Темы, первая — та, по которой подсказка
 * @param methods         отмеченные Методы
 * @param characteristics отмеченные Характеристики
 */
public record ProblemForm(ExamPart part,
                          Set<Long> topics,
                          Set<Long> methods,
                          Set<Long> characteristics) {

    public ProblemForm {
        topics = orderedCopy(topics);
        methods = orderedCopy(methods);
        characteristics = orderedCopy(characteristics);
    }

    /** Пустая форма заведения; Тема, с которой пришли, выбрана заранее. */
    static ProblemForm startingAt(Long topic) {
        return new ProblemForm(null, topic == null ? Set.of() : Set.of(topic), Set.of(), Set.of());
    }

    /** Форма правки, заполненная сохранённой Задачей. */
    static ProblemForm of(Problem problem) {
        return new ProblemForm(problem.part(),
                ids(problem.topics().stream().map(id -> id.value()).toList()),
                ids(problem.methods().stream().map(id -> id.value()).toList()),
                ids(problem.characteristics().stream().map(id -> id.value()).toList()));
    }

    /** Форма после отказа — ровно то, что было прислано. */
    static ProblemForm sent(ExamPart part, List<Long> topics,
                            List<Long> methods, List<Long> characteristics) {
        return new ProblemForm(part, ids(topics), ids(methods), ids(characteristics));
    }

    /** Первая отмеченная Тема или {@code null}: по ней строится подсказка Методов. */
    public Long firstTopic() {
        return topics.isEmpty() ? null : topics.iterator().next();
    }

    private static Set<Long> ids(Collection<Long> values) {
        return values == null ? Set.of() : orderedCopy(values);
    }

    private static Set<Long> orderedCopy(Collection<Long> values) {
        Set<Long> copy = new LinkedHashSet<>();
        values.stream().filter(value -> value != null).forEach(copy::add);
        return Collections.unmodifiableSet(copy);
    }
}
