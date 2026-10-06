package ru.locus.problem;

/**
 * Строка сборки, как её рисует форма: черновик и страницы «с … по …».
 *
 * Отдельная от {@link PdfAssemblyOrder.Line} запись: строке формы нужны
 * имя исходника и число страниц, а порядку сборки — только идентификатор.
 * Одна разметка строки на загрузку исходника и на перерисовку формы после
 * отказа — фрагмент {@code problem/assembly :: row}.
 */
public record AssemblyRow(AssemblyDraft draft, int from, int to) {

    /** Строка только что загруженного исходника: все его страницы. */
    public static AssemblyRow whole(AssemblyDraft draft) {
        return new AssemblyRow(draft, 1, draft.pageCount());
    }

    public boolean image() {
        return draft.kind() == AssemblyDraft.Kind.IMAGE;
    }
}
