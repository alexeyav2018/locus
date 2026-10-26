package ru.locus.warmup;

import java.util.ArrayList;
import java.util.List;
import ru.locus.problem.ProblemId;

/**
 * Одна клетка итогов участника с формы Разминки: Задача и выбранное
 * значение.
 *
 * <p>{@code value == null} — пустая клетка: Ученик эту Задачу не писал
 * или до неё не дошли. Пустая клетка не хранится — сервис удаляет строку
 * итога, а не пишет «пусто» (ADR-0061); «не справился» — это
 * {@link WarmupOutcome#FAILED}, суждение, и путать их нельзя. Устроено
 * по образцу {@link ru.locus.mastery.Mark}.
 *
 * @param value итог; {@code null} — пустая клетка
 */
public record Outcome(ProblemId problem, WarmupOutcome value) {

    public Outcome {
        if (problem == null) {
            throw new IllegalArgumentException("У итога Разминки должна быть Задача");
        }
    }

    /**
     * Итоги из формы участника: два параллельных списка {@code problem}
     * и {@code outcome}, по элементу на Задачу состава, собираются
     * по индексу. Пустой {@code outcome} — пустая клетка. Списки контроллер
     * читает сырыми из запроса по той же причине, что у
     * {@link ru.locus.mastery.Mark#fromForm}: конвертер Spring превращает
     * единственную пустую строку в пустой список, и длины разошлись бы.
     *
     * @param problems значения {@code problem}; {@code null} — полей нет
     * @param values   значения {@code outcome}; {@code null} — полей нет
     * @throws IllegalArgumentException разошедшиеся длины — испорченная
     *         форма; неизвестное значение отвергает
     *         {@link WarmupOutcome#valueOf}
     */
    public static List<Outcome> fromForm(String[] problems, String[] values) {
        String[] p = problems == null ? new String[0] : problems;
        String[] v = values == null ? new String[0] : values;
        if (p.length != v.length) {
            throw new IllegalArgumentException("Форма итогов повреждена: Задачи и итоги не сходятся по числу");
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            WarmupOutcome value = v[i].isBlank() ? null : WarmupOutcome.valueOf(v[i]);
            outcomes.add(new Outcome(new ProblemId(Long.parseLong(p[i])), value));
        }
        return List.copyOf(outcomes);
    }
}
