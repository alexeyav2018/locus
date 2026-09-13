package ru.locus.assignment;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Задание — набор Задач, выданный одному Ученику, со сроком и охватом
 * теории (ADR-0016).
 *
 * <p>Поля «сдано» у Задания нет и быть не должно: «не сдано» — это «срок
 * прошёл и Работы нет», и вычисляется оно при каждом показе из срока,
 * часов и ответа области Работ (инвариант 12 domain-model.md, ADR-0016).
 * Хранимый флаг пришлось бы кому-то проставлять и снимать, а забытое
 * действие дало бы Задание, которое числится сданным без Работы или
 * несданным при ней, — и обнаружилось бы это только по жалобе. Состав
 * колонок сторожит {@code NotSubmittedIsNotStoredTest}.
 *
 * <p>Раздача необязательна: {@code null} — законное состояние, Задание
 * выдано лично, а не Группе. Сама Раздача помнит не Группу, а её имя
 * (см. {@link AssignmentBatch}).
 *
 * <p>Владелец обязателен: Задание — личный контур, и запись без владельца —
 * это запись, которую увидят все (инвариант 11 domain-model.md, ADR-0027).
 * Ученик обязателен, потому что Задание персонально: персональны Работы,
 * вердикты и отметки, которые на нём вырастут (ADR-0016). Обе даты и охват
 * обязательны: без срока не вычислить «не сдано», без охвата — не решить,
 * какую теорию показывать.
 *
 * <p>Состав — хотя бы одна Задача, без повторов, в порядке выдачи: учитель
 * выбирает Задачи в порядке, который для него что-то значит, и список
 * без порядка перемешался бы от чтения к чтению. Повторы снимает сервис
 * до выдачи; сюда они дойти не должны, и запись отказывает, а не чинит
 * молча. После выдачи состав и охват не меняются (ADR-0037): единственная
 * правка — перенос срока.
 *
 * @param batch    Раздача, если Задание выдано Группе; {@code null} —
 *                 выдано лично
 * @param problems Задачи состава, 1..n, без повторов, порядок сохраняется
 */
public record Assignment(AssignmentId id,
                         UserId owner,
                         StudentId student,
                         AssignmentBatchId batch,
                         LocalDate issuedOn,
                         LocalDate dueDate,
                         TheoryScope theoryScope,
                         List<ProblemId> problems) {

    public Assignment {
        if (owner == null) {
            throw new IllegalArgumentException("У Задания должен быть владелец");
        }
        if (student == null) {
            throw new IllegalArgumentException("У Задания должен быть Ученик");
        }
        if (issuedOn == null) {
            throw new IllegalArgumentException("У Задания должна быть дата выдачи");
        }
        if (dueDate == null) {
            throw new IllegalArgumentException("У Задания должен быть срок");
        }
        if (theoryScope == null) {
            throw new IllegalArgumentException("У Задания должен быть охват теории");
        }
        if (problems == null || problems.isEmpty()) {
            throw new IllegalArgumentException("Заданию нужна хотя бы одна Задача");
        }
        Set<ProblemId> seen = new HashSet<>();
        for (ProblemId problem : problems) {
            if (!seen.add(problem)) {
                throw new IllegalArgumentException("Задача № " + problem.value() + " в Задании повторяется");
            }
        }
        problems = List.copyOf(problems);
    }

    /** Выдано ли Задание Группой — то есть есть ли у него Раздача. */
    public boolean isFromBatch() {
        return batch != null;
    }
}
