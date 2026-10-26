package ru.locus.warmup;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Разминка — Задачи, которые Ученик или Группа решают на занятии
 * (ADR-0061).
 *
 * <p>Срока, охвата теории, файлов и «не сдано» у Разминки нет по построению:
 * сдавать нечего, Задачи решены на занятии. Дата — день, когда её провели;
 * по ней учитель видит, какие Задачи когда решались.
 *
 * <p>Участники — один Ученик либо действующие члены Группы на момент
 * заведения. Хранятся они, а не Группа: состав Группы меняется, а Разминка
 * остаётся такой, какой была. Имя Группы помнится текстом, как у
 * {@link ru.locus.assignment.AssignmentBatch}; {@code null} — Разминка
 * Ученику.
 *
 * <p>Владелец обязателен: Разминка — личный контур, и запись без владельца —
 * это запись, которую увидят все (инвариант 11 domain-model.md, ADR-0027).
 *
 * <p>Участники и Задачи — хотя бы по одному, без повторов. Повторы снимает
 * сервис до заведения; сюда они дойти не должны, и запись отказывает,
 * а не чинит молча. Задачи — в порядке выбора, участники — в порядке,
 * который отдаёт репозиторий. После заведения состав не меняется: ошиблись —
 * Разминку удаляют и заводят заново (ADR-0061).
 *
 * @param groupName имя Группы на момент заведения; {@code null} — Разминка
 *                  Ученику
 * @param students  участники, 1..n, без повторов
 * @param problems  Задачи состава, 1..n, без повторов, порядок сохраняется
 */
public record Warmup(WarmupId id,
                     UserId owner,
                     LocalDate heldOn,
                     String groupName,
                     List<StudentId> students,
                     List<ProblemId> problems) {

    public Warmup {
        if (owner == null) {
            throw new IllegalArgumentException("У Разминки должен быть владелец");
        }
        if (heldOn == null) {
            throw new IllegalArgumentException("У Разминки должна быть дата");
        }
        if (groupName != null && groupName.isBlank()) {
            throw new IllegalArgumentException("Имя Группы в Разминке не может быть пустым");
        }
        if (students == null || students.isEmpty()) {
            throw new IllegalArgumentException("Разминке нужен хотя бы один участник");
        }
        if (problems == null || problems.isEmpty()) {
            throw new IllegalArgumentException("Разминке нужна хотя бы одна Задача");
        }
        Set<StudentId> seenStudents = new HashSet<>();
        for (StudentId student : students) {
            if (!seenStudents.add(student)) {
                throw new IllegalArgumentException("Ученик " + student.value() + " в Разминке повторяется");
            }
        }
        Set<ProblemId> seenProblems = new HashSet<>();
        for (ProblemId problem : problems) {
            if (!seenProblems.add(problem)) {
                throw new IllegalArgumentException("Задача № " + problem.value() + " в Разминке повторяется");
            }
        }
        students = List.copyOf(students);
        problems = List.copyOf(problems);
    }

    /** Проведена ли Разминка Группе — то есть помнит ли она имя Группы. */
    public boolean isForGroup() {
        return groupName != null;
    }

    /** Участвует ли Ученик в Разминке. */
    public boolean hasStudent(StudentId student) {
        return students.contains(student);
    }
}
