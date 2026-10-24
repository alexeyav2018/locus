package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Задача 1.2: содержимое Черновика Задачи — копии списков и пустое вместо
 * {@code null}; изменение меняет только названное (design.md, Р3).
 */
class ProblemDraftTest {

    private static final PdfAssemblyOrder PHOTO = new PdfAssemblyOrder(List.of(
            new PdfAssemblyOrder.Line(new AssemblyDraftId(7), 1, 1, new CropFrame(0.1, 0.2, 0.5, 0.3))));

    @Test
    void contentCopiesItsListsAndTurnsNullIntoEmpty() {
        List<String> doubts = new ArrayList<>(List.of("8 похоже на 9"));
        ProblemDraftContent content = new ProblemDraftContent(null, null, null, null, null, doubts,
                null, null, null, null);
        doubts.add("ещё одно");

        assertThat(content.doubts()).containsExactly("8 похоже на 9");
        assertThat(content.conditionText()).isEmpty();
        assertThat(content.solutionText()).isEmpty();
        assertThat(content.condition().lines()).isEmpty();
        assertThat(content.solution().lines()).isEmpty();
        assertThat(content.topics()).isEmpty();
        assertThat(content.newMethods()).isEmpty();
        assertThatThrownBy(() -> content.doubts().add("снаружи"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changeReplacesOnlyWhatItNames() {
        ProblemDraftContent current = content();
        ProblemDraftChange change = new ProblemDraftChange(null, null, "новое решение", null, null, null,
                null, null, null, null);

        ProblemDraftContent changed = change.applyTo(current);

        assertThat(changed.solutionText()).isEqualTo("новое решение");
        assertThat(changed).usingRecursiveComparison().ignoringFields("solutionText").isEqualTo(current);
    }

    @Test
    void emptyPartRemovesThePartWhileNullKeepsIt() {
        ProblemDraftContent current = content();
        ProblemDraftChange keep = new ProblemDraftChange(null, null, null, null, null, null, null, null, null, null);
        ProblemDraftChange remove = new ProblemDraftChange(Optional.empty(), null, null, null, null, null,
                null, null, null, null);

        assertThat(keep.applyTo(current).part()).isEqualTo(ExamPart.SECOND);
        assertThat(remove.applyTo(current).part()).isNull();
    }

    @Test
    void emptyListClearsWhatNullWouldKeep() {
        ProblemDraftChange clear = new ProblemDraftChange(null, null, null, new PdfAssemblyOrder(List.of()), null,
                List.of(), null, null, null, null);

        ProblemDraftContent changed = clear.applyTo(content());

        assertThat(changed.condition().lines()).isEmpty();
        assertThat(changed.doubts()).isEmpty();
        assertThat(changed.solution()).isEqualTo(PHOTO);
    }

    @Test
    void wholeChangeOfContentGivesTheSameContent() {
        ProblemDraftContent next = new ProblemDraftContent(ExamPart.FIRST, "у", "р", null, null, null,
                null, null, null, null);

        assertThat(ProblemDraftChange.of(next).applyTo(content())).isEqualTo(next);
    }

    private static ProblemDraftContent content() {
        return new ProblemDraftContent(
                ExamPart.SECOND,
                "$\\log_2 ⟦8⟧ = 3$",
                "Решение",
                PHOTO,
                PHOTO,
                List.of("8 похоже на 9"),
                List.of(new TaxonomyNodeId(3)),
                List.of(new SolutionMethodId(4)),
                List.of(new CharacteristicId(5)),
                List.of("Метод рационализации"));
    }
}
