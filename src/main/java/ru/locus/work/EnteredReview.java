package ru.locus.work;

import java.util.Map;
import ru.locus.mastery.Cell;
import ru.locus.mastery.MasteryCell;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ProblemId;

/**
 * Введённое в отклонённую форму приёма или вердикта одной Задачи —
 * чтобы экран приёма показал её снова с выбранным, и учитель не набирал
 * вердикт и отметки заново (design.md {@code work-mastery-at-receipt},
 * решение 5).
 *
 * <p>Своя запись, а не {@code ShellAdvice.Shell.params}: там одно
 * значение на имя, а значений ячеек — по одному на ячейку под тем же
 * именем {@code status}. Шаблон берёт выбранное отсюда только в блоке
 * той Задачи, чья форма отклонена; прочие формы экрана — обычные.
 *
 * <p>Ни вердикт, ни отметки отсюда не сохраняются: запись живёт один
 * показ и ничего не утверждает о Работе.
 *
 * @param verdict    выбранный вердикт; {@code null} — «не проверять сейчас»
 * @param note       примечание как набрано; {@code null} — поля не было
 * @param receivedOn дата получения текстом, как пришла, — для формы
 *                   приёма; у формы вердикта {@code null}
 * @param statuses   выбранные значения ячеек; «без изменения» — отсутствие
 *                   ключа
 */
public record EnteredReview(ProblemId problem,
                            Verdict verdict,
                            String note,
                            String receivedOn,
                            Map<Cell, MasteryStatus> statuses) {

    public EnteredReview {
        if (problem == null) {
            throw new IllegalArgumentException("Введённое относится к Задаче");
        }
        statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
    }

    /** Относится ли введённое к блоку этой Задачи. */
    public boolean isFor(ProblemId problem) {
        return this.problem.equals(problem);
    }

    /** Значение, выбранное в ячейке; {@code null} — «без изменения». */
    public MasteryStatus statusOf(MasteryCell cell) {
        return statuses.get(cell.cell());
    }
}
