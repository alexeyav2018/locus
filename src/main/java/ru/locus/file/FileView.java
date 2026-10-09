package ru.locus.file;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Что показывает страница просмотра файла внутри системы (ADR-0042).
 *
 * PDF показывается не встроенным просмотром браузера, а столбцом картинок
 * его страниц, отрисованных на сервере (ADR-0052): адрес картинки — адрес
 * страницы просмотра с {@code /pages/{n}}, под теми же правами.
 *
 * @param title      заголовок страницы
 * @param link       временная подписанная ссылка на файл — та же, что выдавалась
 *                   бы в разметку экрана сегодня (ADR-0021)
 * @param image      {@code true} — показать картинкой; иначе — страницами PDF
 * @param fallback   куда ведёт «Назад», если адрес возврата не передан
 * @param pages      картинки первых страниц PDF; у изображения — пусто
 * @param total      сколько страниц в PDF всего; у изображения и у
 *                   неразобранного PDF — ноль
 * @param unreadable PDF не удалось разобрать — показать нечего, остаётся
 *                   «Открыть PDF»
 */
public record FileView(String title, String link, boolean image, String fallback,
                       List<Page> pages, int total, boolean unreadable) {

    public FileView {
        pages = List.copyOf(pages);
    }

    /** Страниц больше, чем показано: страница просмотра называет их общее число. */
    public boolean truncated() {
        return total > pages.size();
    }

    /** Изображение: показывается само, страниц у него нет. */
    public static FileView picture(String title, String link, String fallback) {
        return new FileView(title, link, true, fallback, List.of(), 0, false);
    }

    /**
     * PDF по его оглавлению: картинка каждой из показанных страниц —
     * по адресу {@code viewer + "/pages/" + n}. Пустое оглавление —
     * файл не разобрался или его нет в хранилище.
     *
     * @param viewer адрес самой страницы просмотра, без параметров
     */
    public static FileView pdf(String title, String link, String fallback, String viewer,
                               Optional<PdfOutline> outline) {
        if (outline.isEmpty()) {
            return new FileView(title, link, false, fallback, List.of(), 0, true);
        }
        List<Page> pages = new ArrayList<>();
        List<PdfOutline.PageSize> shown = outline.get().shown();
        for (int i = 0; i < shown.size(); i++) {
            pages.add(new Page(viewer + "/pages/" + (i + 1), i + 1, shown.get(i).width(), shown.get(i).height()));
        }
        return new FileView(title, link, false, fallback, pages, outline.get().pages(), false);
    }

    /** Картинка одной страницы PDF: адрес, номер с единицы и размер в пикселях. */
    public record Page(String src, int number, int width, int height) {
    }
}
