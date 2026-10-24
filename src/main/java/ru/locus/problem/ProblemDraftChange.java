package ru.locus.problem;

import java.util.List;
import java.util.Optional;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Изменение Черновика Задачи: каждая часть необязательна, {@code null} —
 * не трогать (design.md, Р3). Страница вычитки шлёт все части всегда,
 * инструмент Помощника — только названные.
 *
 * Часть — {@link Optional}, а не голое значение: у неё три случая —
 * не трогать ({@code null}), снять ({@code Optional.empty()}) и задать.
 * У прочих частей «снять» — пустая строка или пустой список.
 */
public record ProblemDraftChange(Optional<ExamPart> part,
                                 String conditionText,
                                 String solutionText,
                                 PdfAssemblyOrder condition,
                                 PdfAssemblyOrder solution,
                                 List<String> doubts,
                                 List<TaxonomyNodeId> topics,
                                 List<SolutionMethodId> methods,
                                 List<CharacteristicId> characteristics,
                                 List<String> newMethods) {

    /** Изменение, заменяющее всё содержимое, — так пишет страница вычитки. */
    public static ProblemDraftChange of(ProblemDraftContent content) {
        return new ProblemDraftChange(
                Optional.ofNullable(content.part()),
                content.conditionText(),
                content.solutionText(),
                content.condition(),
                content.solution(),
                content.doubts(),
                content.topics(),
                content.methods(),
                content.characteristics(),
                content.newMethods());
    }

    /** Содержимое после изменения: названное заменено, прочее прежнее. */
    public ProblemDraftContent applyTo(ProblemDraftContent current) {
        return new ProblemDraftContent(
                part == null ? current.part() : part.orElse(null),
                or(conditionText, current.conditionText()),
                or(solutionText, current.solutionText()),
                or(condition, current.condition()),
                or(solution, current.solution()),
                or(doubts, current.doubts()),
                or(topics, current.topics()),
                or(methods, current.methods()),
                or(characteristics, current.characteristics()),
                or(newMethods, current.newMethods()));
    }

    private static <T> T or(T changed, T current) {
        return changed == null ? current : changed;
    }
}
