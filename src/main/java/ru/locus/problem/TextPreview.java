package ru.locus.problem;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import ru.locus.file.PdfOutline;
import ru.locus.file.PdfPages;

/**
 * Что показывает блок предпросмотра формы Задачи (design.md изменения
 * {@code problem-text}, Р9): страницы отрисовки картинками либо отказ
 * отрисовки на том же месте.
 *
 * Картинки вставляются в страницу адресами {@code data:}: превью нигде
 * не хранится и ссылок на себя не имеет — закрыл страницу, и его нет.
 * Страниц не больше, чем показывает страница просмотра
 * ({@link PdfPages#SHOWN}): решение на страницу — одна-две.
 *
 * @param condition страницы отрисовки условия; пусто — текст условия не дан
 * @param solution  страницы отрисовки решения; пусто — текст решения не дан
 * @param refusal   отказ отрисовки; {@code null} — отрисовалось
 */
public record TextPreview(List<String> condition, List<String> solution, String refusal) {

    public TextPreview {
        condition = List.copyOf(condition);
        solution = List.copyOf(solution);
    }

    static TextPreview of(RenderedText rendered) {
        return new TextPreview(pages(rendered.condition()), pages(rendered.solution()), null);
    }

    static TextPreview refused(String refusal) {
        return new TextPreview(List.of(), List.of(), refusal);
    }

    private static List<String> pages(byte[] pdf) {
        if (pdf == null) {
            return List.of();
        }
        int shown = PdfPages.outline(pdf).map(PdfOutline::pages).orElse(0);
        List<String> pages = new ArrayList<>();
        for (int page = 1; page <= Math.min(shown, PdfPages.SHOWN); page++) {
            PdfPages.render(pdf, page).ifPresent(jpeg ->
                    pages.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)));
        }
        return pages;
    }
}
