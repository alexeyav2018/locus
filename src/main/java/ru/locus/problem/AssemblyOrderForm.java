package ru.locus.problem;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор строк сборки из полей формы — общий для формы Задачи и страницы
 * вычитки Черновика Задачи: обе показывают одну сцену рамок
 * ({@code problem/assembly :: row}) с одними именами полей, и разбор
 * у них один (design.md изменения {@code problem-intake}, Р6).
 */
final class AssemblyOrderForm {

    private AssemblyOrderForm() {
    }

    /**
     * Порядок сборки из повторяющихся полей строк: «черновик», «с», «по»,
     * «рамка» идут в строках формы по одному, и i-е значения каждого списка —
     * одна строка. Незаполненная страница — нуль: такой диапазон отклонит
     * сборка, назвав источник и число его страниц, а не разбор формы безлико.
     *
     * <p>Рамка здесь разбирается снисходительно: испорченная считается
     * отсутствующей, чтобы порядок можно было перерисовать в форме после
     * отказа. Сам отказ испорченной рамке даёт {@link #requireFrames} внутри
     * обработки — иначе вместо формы с текстом была бы страница ошибки.
     */
    static PdfAssemblyOrder order(List<Long> draftIds, List<Integer> from, List<Integer> to,
                                  List<String> crops) {
        if (draftIds == null) {
            return new PdfAssemblyOrder(List.of());
        }
        List<PdfAssemblyOrder.Line> lines = new ArrayList<>();
        for (int i = 0; i < draftIds.size(); i++) {
            if (draftIds.get(i) == null) {
                continue;
            }
            lines.add(new PdfAssemblyOrder.Line(new AssemblyDraftId(draftIds.get(i)), page(from, i), page(to, i),
                    frameOrNull(crops, i)));
        }
        return new PdfAssemblyOrder(lines);
    }

    /** Отказ формы, если хоть одна присланная рамка испорчена или неверна. */
    static void requireFrames(List<String> crops) {
        if (crops != null) {
            crops.forEach(CropFrame::parse);
        }
    }

    private static CropFrame frameOrNull(List<String> crops, int i) {
        if (crops == null || i >= crops.size()) {
            return null;
        }
        try {
            return CropFrame.parse(crops.get(i));
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    private static int page(List<Integer> pages, int i) {
        return pages == null || i >= pages.size() || pages.get(i) == null ? 0 : pages.get(i);
    }
}
