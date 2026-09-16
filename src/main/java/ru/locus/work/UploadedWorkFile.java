package ru.locus.work;

/**
 * Присланный файл Работы до укладки в хранилище: содержимое и тип, как его
 * назвал браузер.
 *
 * Своя запись, а не {@link ru.locus.problem.UploadedFile} Задач и не
 * {@link ru.locus.theory.UploadedContent} теории: области друг от друга
 * не зависят и зависеть не должны, а состав здесь свой — файлов много
 * за одно действие, и каждый либо изображение, либо PDF.
 *
 * Пережатия здесь нет: решает вызывающий, по типу содержимого
 * (standards.md, «Файлы»). Для Работы решает {@link StudentWorkService} —
 * изображение пережимает, PDF кладёт как есть, прочее отклоняет.
 * Пустой файл ({@link #isEmpty()}) — это «поле формы без выбранного файла»,
 * и сервис его отбрасывает, а не отклоняет приём.
 */
public record UploadedWorkFile(byte[] content, String contentType) {

    public boolean isEmpty() {
        return content == null || content.length == 0;
    }
}
