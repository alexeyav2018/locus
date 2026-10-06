package ru.locus.file;

/**
 * Что показывает страница просмотра файла внутри системы (ADR-0042).
 *
 * @param title    заголовок страницы
 * @param link     временная подписанная ссылка на файл — та же, что выдавалась
 *                 бы в разметку экрана сегодня (ADR-0021)
 * @param image    {@code true} — показать картинкой; иначе встроенным просмотром PDF
 * @param fallback куда ведёт «Назад», если адрес возврата не передан
 */
public record FileView(String title, String link, boolean image, String fallback) {

    /** Вид просмотра по типу файла, который записан в его ключе. */
    public static FileView of(String title, String link, FileKey key, String fallback) {
        return new FileView(title, link, FileType.isImage(FileType.contentTypeFor(key)), fallback);
    }
}
