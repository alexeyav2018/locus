package ru.locus.mastery;

import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Ячейка владения в форме простановки — на экране приёма Работ и на странице
 * Разминки: пара «Тема × Метод» с подписями, текущим значением и справкой
 * по двум основаниям порознь (ADR-0011, ADR-0039, ADR-0061).
 *
 * <p>Ячейки не хранятся, а вычисляются на каждый показ из разметки Задачи —
 * Задания или Разминки (standards.md, «Вычислимое вычисляется
 * в запросе»): кандидат — каждая пара из {@code topics × methods} Задачи.
 * Ячейка без строки в базе показывается как {@link MasteryStatus#UNKNOWN} —
 * это отсутствие суждения, а не хранимое значение.
 *
 * <p>Справка считает суждения об этом Ученике на Задачах, размеченных
 * и этой Темой, и этим Методом, — по каждому основанию отдельно:
 * {@code works} — проверенные Работы (непроверенная не входит, ADR-0039),
 * {@code warmups} — Итоги Разминок (пустая клетка не хранится и не входит).
 * Основания не складываются: Работа, решённая дома, и задача у доски
 * говорят о владении разное, и взвешивает их учитель (ADR-0061).
 * Справка — подсказка учителю, а не основание для автоматической отметки
 * (ADR-0011, ADR-0014): ставит только человек.
 *
 * @param topicPath  полный путь Темы — подпись ячейки; учитель в спешке
 *                   должен видеть, в какую ячейку ставит
 * @param methodName имя Метода — вторая половина подписи
 * @param status     текущее значение; {@code UNKNOWN}, если строки нет
 * @param works      по проверенным Работам: с вердиктом «верно» из всех проверенных
 * @param warmups    по Итогам Разминок: «справился» из всех итогов
 */
public record MasteryCell(TaxonomyNodeId topic,
                          String topicPath,
                          SolutionMethodId method,
                          String methodName,
                          MasteryStatus status,
                          Solved works,
                          Solved warmups) {

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
        if (works == null) {
            throw new IllegalArgumentException("У ячейки владения должна быть справка по Работам; без Работ — Solved.NONE");
        }
        if (warmups == null) {
            throw new IllegalArgumentException("У ячейки владения должна быть справка по Разминкам; без итогов — Solved.NONE");
        }
    }

    /** Ключ ячейки. */
    public Cell cell() {
        return new Cell(topic, method);
    }

    /**
     * Справка одной фразой — «Работы: N из M · разминки: K из L», оба
     * основания всегда, и с нулями: «0 из 0» говорит, что суждений этого
     * рода нет, и учитель не гадает, забыто основание или пусто. Фраза
     * собирается здесь, а не в шаблоне: шаблоны не вычисляют (standards.md),
     * а показывают её и экран приёма, и страница Разминки.
     */
    public String hint() {
        return "Работы: " + works.succeeded() + " из " + works.judged()
                + " · разминки: " + warmups.succeeded() + " из " + warmups.judged();
    }
}
