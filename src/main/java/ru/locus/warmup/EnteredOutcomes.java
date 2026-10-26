package ru.locus.warmup;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.locus.mastery.Cell;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryCell;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;

/**
 * Введённое в отклонённую форму «Итоги и владение» одного участника —
 * чтобы страница Разминки показала её снова с выбранным, и Учитель
 * не выбирал итоги и отметки заново (design.md {@code warmup}, решение 6).
 *
 * <p>Устроено как {@link ru.locus.work.EnteredReview}: своя запись, а не
 * {@code ShellAdvice.Shell.params}, потому что значений под одним именем
 * несколько — по итогу на Задачу и по значению на ячейку. Фрагмент
 * {@code mastery-cells} спрашивает у неё {@link #statusOf} так же, как
 * у {@code EnteredReview}. Шаблон берёт выбранное отсюда только в блоке
 * того участника, чья форма отклонена.
 *
 * <p>Ни итоги, ни отметки отсюда не сохраняются: запись живёт один показ.
 *
 * @param outcomes выбранные итоги по Задачам; пустая клетка — ключ
 *                 со значением {@code null}, Задачи нет в форме — ключа нет
 * @param statuses выбранные значения ячеек; «без изменения» — отсутствие
 *                 ключа
 */
public record EnteredOutcomes(StudentId student,
                              Map<ProblemId, WarmupOutcome> outcomes,
                              Map<Cell, MasteryStatus> statuses) {

    public EnteredOutcomes {
        if (student == null) {
            throw new IllegalArgumentException("Введённое относится к участнику");
        }
        outcomes = outcomes == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(outcomes));
        statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
    }

    /**
     * Введённое из разобранных итогов и отметок. Испорченная форма
     * отказывает ещё при разборе — тогда списки пусты, и блок показывается
     * с сохранёнными итогами и «без изменения» во всех ячейках.
     */
    public static EnteredOutcomes of(StudentId student, List<Outcome> outcomes, List<Mark> marks) {
        Map<ProblemId, WarmupOutcome> chosen = new HashMap<>();
        for (Outcome outcome : outcomes) {
            chosen.put(outcome.problem(), outcome.value());
        }
        Map<Cell, MasteryStatus> statuses = new HashMap<>();
        for (Mark mark : marks) {
            if (mark.status() != null) {
                statuses.put(mark.cell(), mark.status());
            }
        }
        return new EnteredOutcomes(student, chosen, statuses);
    }

    /** Относится ли введённое к блоку этого участника. */
    public boolean isFor(StudentId student) {
        return this.student.equals(student);
    }

    /** Была ли Задача в отклонённой форме — иначе показывается сохранённый итог. */
    public boolean hasOutcomeFor(ProblemId problem) {
        return outcomes.containsKey(problem);
    }

    /** Итог, выбранный по Задаче; {@code null} — пустая клетка. */
    public WarmupOutcome outcomeOf(ProblemId problem) {
        return outcomes.get(problem);
    }

    /** Значение, выбранное в ячейке; {@code null} — «без изменения». */
    public MasteryStatus statusOf(MasteryCell cell) {
        return statuses.get(cell.cell());
    }
}
