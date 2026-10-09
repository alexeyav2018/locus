package ru.locus.file;

import java.util.List;

/**
 * Что известно о PDF до отрисовки: сколько в нём страниц и какого размера
 * картинками будут первые из них (ADR-0052).
 *
 * Размеры нужны странице просмотра для {@code width} и {@code height}
 * у картинок: столбец ленивых картинок без них прыгает, пока страницы
 * догружаются.
 *
 * @param pages общее число страниц файла
 * @param shown размеры картинок первых страниц — не больше
 *              {@link PdfPages#SHOWN}, по порядку с первой
 */
public record PdfOutline(int pages, List<PageSize> shown) {

    public PdfOutline {
        shown = List.copyOf(shown);
    }

    /** Размер картинки страницы в пикселях — такой, какой отдаст {@link PdfPages#render}. */
    public record PageSize(int width, int height) {
    }
}
