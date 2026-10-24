package ru.locus.problem;

import java.util.List;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Содержимое Черновика Задачи — то, что распознал Помощник и правит
 * учитель (ADR-0059): тексты, строки сборки Улик, сомнительные места,
 * предложенная разметка и названные новые Методы.
 *
 * Любая часть может быть пустой: черновик — не Задача, правила её состава
 * проверяет заведение Задачи. Пустое здесь — пустая строка и пустой
 * список, а не {@code null}: «не трогать» — забота {@link ProblemDraftChange}.
 *
 * @param part            Часть; {@code null} — ещё не предложена
 * @param conditionText   текст условия с пометками {@code ⟦…⟧}
 * @param solutionText    текст решения с пометками {@code ⟦…⟧}
 * @param condition       строки сборки Улики условия
 * @param solution        строки сборки Улики решения
 * @param doubts          причины сомнений, по строке на пометку
 * @param newMethods      имена Методов, которых в словаре нет; в словарь
 *                        они не попадают
 */
public record ProblemDraftContent(ExamPart part,
                                  String conditionText,
                                  String solutionText,
                                  PdfAssemblyOrder condition,
                                  PdfAssemblyOrder solution,
                                  List<String> doubts,
                                  List<TaxonomyNodeId> topics,
                                  List<SolutionMethodId> methods,
                                  List<CharacteristicId> characteristics,
                                  List<String> newMethods) {

    public ProblemDraftContent {
        conditionText = conditionText == null ? "" : conditionText;
        solutionText = solutionText == null ? "" : solutionText;
        condition = condition == null ? new PdfAssemblyOrder(List.of()) : condition;
        solution = solution == null ? new PdfAssemblyOrder(List.of()) : solution;
        doubts = copy(doubts);
        topics = copy(topics);
        methods = copy(methods);
        characteristics = copy(characteristics);
        newMethods = copy(newMethods);
    }

    /** Порядок сборки Улики слота. */
    public PdfAssemblyOrder lines(ProblemSlot slot) {
        return slot == ProblemSlot.CONDITION ? condition : solution;
    }

    /** Текст слота. */
    public String text(ProblemSlot slot) {
        return slot == ProblemSlot.CONDITION ? conditionText : solutionText;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
