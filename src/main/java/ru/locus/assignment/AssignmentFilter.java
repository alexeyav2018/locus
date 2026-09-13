package ru.locus.assignment;

import java.time.LocalDate;
import ru.locus.student.StudentId;

/**
 * Условия сводки Заданий (С7 scenarios.md): Ученик, Раздача, период срока
 * и переключатель «только несданные». Каждое условие необязательно;
 * заданные соединяются по «и».
 *
 * <p>Период — по сроку, обе границы включительно; открытая граница —
 * {@code null}. Перепутанные границы — отказ, а не пустой список: пустая
 * сводка выглядела бы как честное «Заданий нет».
 *
 * <p>«Только несданные» — не условие запроса, а отбор после чтения:
 * «не сдано» зависит от часов и от ответа области Работ, которого в SQL
 * нет (design.md, «„Не сдано“ — функция в сервисе от трёх входов»).
 *
 * @param student          Ученик или {@code null} — любой
 * @param batch            Раздача или {@code null} — любая, включая выданные
 *                         лично
 * @param from             начало периода срока или {@code null}
 * @param to               конец периода срока или {@code null}
 * @param onlyNotSubmitted показывать только несданные
 */
public record AssignmentFilter(StudentId student,
                               AssignmentBatchId batch,
                               LocalDate from,
                               LocalDate to,
                               boolean onlyNotSubmitted) {

    public AssignmentFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Начало периода позже его конца");
        }
    }

    /** Без условий: все Задания вошедшего Учителя. */
    public static AssignmentFilter none() {
        return new AssignmentFilter(null, null, null, null, false);
    }
}
