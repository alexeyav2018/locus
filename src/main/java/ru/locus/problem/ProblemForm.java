package ru.locus.problem;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Что отмечено и введено в форме Задачи — один источник для всех полей.
 *
 * Форма показывается в четырёх положениях: пустая с Темой, с которой пришли;
 * заполненная сохранённой Задачей; после отказа сервиса и после
 * предпросмотра — заполненная присланным. Шаблон отмечает поля только
 * по этой записи и не выбирает источник сам: выбирай он в каждом поле между
 * Задачей, Темой и запросом, поля разъехались бы молча (design.md,
 * «Объект формы»).
 *
 * Идентификаторы — числами, как их шлёт и сравнивает форма; порядок Тем
 * сохраняется, потому что подсказку Методов строит первая.
 *
 * @param part            Часть ЕГЭ; {@code null} — не выбрана
 * @param topics          отмеченные Темы, первая — та, по которой подсказка
 * @param methods         отмеченные Методы
 * @param characteristics отмеченные Характеристики
 * @param kind            вид Задачи: с текстом или без (design.md изменения
 *                        {@code problem-text}, Р2)
 * @param conditionText   введённый или хранимый текст условия; {@code null} — нет
 * @param solutionText    введённый или хранимый текст решения; {@code null} — нет
 * @param version         версия текста, с которой открыта страница (Р7)
 */
public record ProblemForm(ExamPart part,
                          Set<Long> topics,
                          Set<Long> methods,
                          Set<Long> characteristics,
                          Kind kind,
                          String conditionText,
                          String solutionText,
                          int version) {

    /**
     * Вид Задачи в форме заведения. Поля файлов у слотов одни и те же,
     * а значат разное: у Задачи без текста — PDF слота, у Задачи с текстом —
     * его Улику (Р4). Поэтому вид выбирается явно, а не угадывается по тому,
     * какие поля заполнены.
     */
    public enum Kind {
        TEXT,
        FILES
    }

    public ProblemForm {
        topics = orderedCopy(topics);
        methods = orderedCopy(methods);
        characteristics = orderedCopy(characteristics);
    }

    /**
     * Пустая форма заведения; Тема, с которой пришли, выбрана заранее.
     * Вид — «с текстом»: текст Задачи первичен (ADR-0055).
     */
    static ProblemForm startingAt(Long topic) {
        return new ProblemForm(null, topic == null ? Set.of() : Set.of(topic), Set.of(), Set.of(),
                Kind.TEXT, null, null, 0);
    }

    /** Форма правки, заполненная сохранённой Задачей вместе с её текстом. */
    static ProblemForm of(Problem problem) {
        ProblemText text = problem.text();
        return new ProblemForm(problem.part(),
                ids(problem.topics().stream().map(id -> id.value()).toList()),
                ids(problem.methods().stream().map(id -> id.value()).toList()),
                ids(problem.characteristics().stream().map(id -> id.value()).toList()),
                text == null ? Kind.FILES : Kind.TEXT,
                text == null ? null : text.condition(),
                text == null ? null : text.solution(),
                text == null ? 0 : text.version());
    }

    /** Форма разметки после отказа — ровно то, что было прислано. */
    static ProblemForm sent(ExamPart part, List<Long> topics,
                            List<Long> methods, List<Long> characteristics) {
        return sent(part, topics, methods, characteristics, Kind.FILES, null, null);
    }

    /** Форма заведения после отказа или предпросмотра — с видом и текстами. */
    static ProblemForm sent(ExamPart part, List<Long> topics, List<Long> methods, List<Long> characteristics,
                            Kind kind, String conditionText, String solutionText) {
        return new ProblemForm(part, ids(topics), ids(methods), ids(characteristics),
                kind, conditionText, solutionText, 0);
    }

    /**
     * Та же форма с присланными текстами и версией — после отказа правки
     * текста или после предпросмотра. Не присланный текст слота остаётся
     * прежним: правка идёт по слоту, и второй слот форма не трогала.
     */
    ProblemForm withTexts(String sentCondition, String sentSolution, int sentVersion) {
        return new ProblemForm(part, topics, methods, characteristics, Kind.TEXT,
                sentCondition != null ? sentCondition : conditionText,
                sentSolution != null ? sentSolution : solutionText,
                sentVersion);
    }

    /** Выбран ли вид «с текстом» — для шаблона, который сравнивает без перечислений. */
    public boolean withText() {
        return kind == Kind.TEXT;
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
