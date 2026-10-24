package ru.locus.problem;

import java.util.ArrayList;
import java.util.List;

/**
 * Пометки сомнительного в тексте Черновика Задачи: фрагмент между {@code ⟦}
 * и {@code ⟧} (ADR-0059; problem-intake, design.md, Р5).
 *
 * Локус текст не разбирает (ADR-0055, п. 4) и здесь лишь снимает два
 * зарезервированных знака: {@link #strip} убирает их, оставляя фрагмент, —
 * внутри формулы это оставляет формулу верной, {@code $\log_2 ⟦8⟧$} →
 * {@code $\log_2 8$}. {@link #fragments} находит помеченные фрагменты
 * с контекстом, чтобы страница вычитки подсветила их.
 *
 * Непарный знак снимается так же, как парный, но фрагмента не даёт:
 * границы сомнительного у него нет, а придумывать её Локус не берётся.
 * Знак внутри открытой пометки — тоже просто знак.
 */
public final class DoubtMarks {

    /** Открывающий знак пометки. */
    public static final char OPEN = '⟦';

    /** Закрывающий знак пометки. */
    public static final char CLOSE = '⟧';

    /** Сколько знаков контекста показывается с каждой стороны фрагмента. */
    static final int CONTEXT = 40;

    private static final String CUT = "…";

    private DoubtMarks() {
    }

    /**
     * Помеченный фрагмент с контекстом — текстом без знаков пометки.
     * Обрезанный контекст начинается или кончается многоточием.
     *
     * @param before контекст перед фрагментом
     * @param marked сам фрагмент
     * @param after  контекст после фрагмента
     */
    public record Fragment(String before, String marked, String after) {
    }

    /** Текст без знаков пометки; фрагменты остаются на месте. */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder stripped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != OPEN && c != CLOSE) {
                stripped.append(c);
            }
        }
        return stripped.toString();
    }

    /** Помеченные фрагменты в порядке текста; без пометок — пусто. */
    public static List<Fragment> fragments(String text) {
        if (text == null) {
            return List.of();
        }
        String stripped = strip(text);
        List<Fragment> fragments = new ArrayList<>();
        int position = 0;
        int opened = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == OPEN) {
                if (opened < 0) {
                    opened = position;
                }
            } else if (c == CLOSE) {
                if (opened >= 0) {
                    fragments.add(fragment(stripped, opened, position));
                    opened = -1;
                }
            } else {
                position++;
            }
        }
        return List.copyOf(fragments);
    }

    private static Fragment fragment(String stripped, int start, int end) {
        int from = boundary(stripped, Math.max(0, start - CONTEXT));
        int to = boundary(stripped, Math.min(stripped.length(), end + CONTEXT));
        return new Fragment(
                (from > 0 ? CUT : "") + stripped.substring(from, start),
                stripped.substring(start, end),
                stripped.substring(end, to) + (to < stripped.length() ? CUT : ""));
    }

    /** Граница контекста не рвёт суррогатную пару пополам. */
    private static int boundary(String text, int index) {
        if (index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))) {
            return index - 1;
        }
        return index;
    }
}
