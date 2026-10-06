package ru.locus.problem;

import java.util.List;

/**
 * Порядок сборки PDF слота Задачи, как его прислала форма: строки
 * «черновик, с, по» в порядке страниц результата (ADR-0044).
 *
 * Черновик здесь — только идентификатор: чей он и есть ли он вообще,
 * проверяет {@link AssemblyDraftService#assemble} от имени вошедшего.
 * У картинки «с» и «по» — единицы: форма шлёт их, чтобы списки полей
 * не разъехались, и сборка их не читает.
 */
public record PdfAssemblyOrder(List<Line> lines) implements ProblemPdf {

    public PdfAssemblyOrder {
        lines = List.copyOf(lines);
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public List<AssemblyDraftId> drafts() {
        return lines.stream().map(Line::draft).distinct().toList();
    }

    /** Строка сборки: черновик и страницы «с … по …», с единицы. */
    public record Line(AssemblyDraftId draft, int from, int to) {
    }
}
