package ru.locus.assignment;

import java.util.List;

/**
 * Прежние выдачи одной Задачи, как их называет строка «уже выдана» формы
 * выдачи: новейшие и число остальных.
 *
 * <p>Строку читают, чтобы заметить случайный повтор, а он почти всегда
 * недавний, — поэтому названы только новейшие, а прочие сосчитаны
 * (design.md изменения {@code assignment-repeat-hint}, решение 2). Сколько
 * назвать, решает {@link AssignmentService#priorIssues}; запись лишь
 * перевозит решённое до шаблона.
 *
 * @param shown новейшие выдачи, новые первыми; хотя бы одна — Задаче
 *              без выдач строки нет вовсе
 * @param more  сколько выдач ещё, сверх названных
 */
public record PriorIssues(List<PriorIssue> shown, int more) {

    public PriorIssues {
        if (shown == null || shown.isEmpty()) {
            throw new IllegalArgumentException("Строка прежних выдач называет хотя бы одну выдачу");
        }
        if (more < 0) {
            throw new IllegalArgumentException("Число прочих выдач не бывает отрицательным");
        }
        shown = List.copyOf(shown);
    }
}
