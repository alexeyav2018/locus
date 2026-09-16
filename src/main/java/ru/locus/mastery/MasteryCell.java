package ru.locus.mastery;

import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Ячейка владения на экране приёма: пара «Тема × Метод» с подписями,
 * текущим значением и справкой «решено N из M» (ADR-0011, ADR-0039).
 *
 * <p>Ячейки не хранятся, а вычисляются на каждый показ из разметки Задачи,
 * по которой принята Работа (standards.md, «Вычислимое вычисляется
 * в запросе»): кандидат — каждая пара из {@code topics × methods} Задачи.
 * Ячейка без строки в базе показывается как {@link MasteryStatus#UNKNOWN} —
 * это отсутствие суждения, а не хранимое значение.
 *
 * <p>Справка считает только проверенные Работы этого Ученика на Задачах,
 * размеченных и этой Темой, и этим Методом: {@code checked} — с вердиктом,
 * {@code solved} — из них с вердиктом «верно». Непроверенная Работа —
 * ни «решено», ни «не решено», и в справку не входит (ADR-0039).
 * Справка — подсказка учителю, а не основание для автоматической отметки
 * (ADR-0011, ADR-0014): ставит только человек.
 *
 * @param topicPath  полный путь Темы — подпись ячейки; учитель в спешке
 *                   должен видеть, в какую ячейку ставит
 * @param methodName имя Метода — вторая половина подписи
 * @param status     текущее значение; {@code UNKNOWN}, если строки нет
 * @param solved     проверенных Работ с вердиктом «верно», 0..checked
 * @param checked    проверенных Работ по паре, 0..n
 */
public record MasteryCell(TaxonomyNodeId topic,
                          String topicPath,
                          SolutionMethodId method,
                          String methodName,
                          MasteryStatus status,
                          int solved,
                          int checked) {

    public MasteryCell {
        if (topic == null) {
            throw new IllegalArgumentException("У ячейки владения должна быть Тема");
        }
        if (topicPath == null) {
            throw new IllegalArgumentException("У ячейки владения должен быть путь Темы");
        }
        if (method == null) {
            throw new IllegalArgumentException("У ячейки владения должен быть Метод");
        }
        if (methodName == null) {
            throw new IllegalArgumentException("У ячейки владения должно быть имя Метода");
        }
        if (status == null) {
            throw new IllegalArgumentException("У ячейки владения должно быть значение; без суждения — UNKNOWN");
        }
        if (checked < 0) {
            throw new IllegalArgumentException("Проверенных Работ не может быть меньше нуля: " + checked);
        }
        if (solved < 0 || solved > checked) {
            throw new IllegalArgumentException("Решённых должно быть от 0 до проверенных (" + checked + "): " + solved);
        }
    }

    /** Ключ ячейки. */
    public Cell cell() {
        return new Cell(topic, method);
    }

    /**
     * Справка одной фразой — «решено N из M». Фраза собирается здесь,
     * а не в шаблоне: шаблоны не вычисляют (standards.md), а показать её
     * могут и экран приёма, и будущие представления {@code mastery-views}.
     */
    public String hint() {
        return "решено " + solved + " из " + checked;
    }
}
