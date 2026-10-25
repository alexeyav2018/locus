package ru.locus.problem;

/**
 * Исходник из набора, принимаемого разом, не подходит для сборки
 * ({@link AssemblyDraftService#upload(java.util.List)}): весь набор
 * отклонён, и вызвавший по позиции называет отклонённый так, как его знает
 * учитель, — например, номером поля.
 */
public class SourceRefusedException extends IllegalArgumentException {

    private final int position;

    /**
     * @param position место исходника в наборе, от нуля
     * @param reason   слова отказа одиночного приёма
     * @param cause    исходная причина одиночного отказа, если была: по ней
     *                 {@link AssemblyRefusals} отличает сбой разбора файла
     *                 от ответа на ввод
     */
    public SourceRefusedException(int position, String reason, Throwable cause) {
        super(reason, cause);
        this.position = position;
    }

    /** Место отклонённого исходника в наборе, от нуля. */
    public int position() {
        return position;
    }
}
