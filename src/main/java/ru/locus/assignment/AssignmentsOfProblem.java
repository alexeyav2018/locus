package ru.locus.assignment;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemUsage;

/**
 * Ответ области Заданий на вопрос библиотеки «использована ли Задача»:
 * с момента выдачи — использована, и Задача замораживается (ADR-0030).
 *
 * <p>Счёт идёт по Заданиям <b>всех</b> Учителей и по владельцу
 * не фильтруется: спрашивает Администратор, у которого личного контура нет
 * вовсе, и подставить владельца некому. Это вопрос библиотеки к личному
 * контуру о своей сущности — класс исключений из ADR-0027, описанный
 * в ADR-0036, и здесь его первая реализация. Границы класса соблюдены:
 * наружу уходит <b>только число</b> — ни Ученика, ни Учителя, ни самого
 * Задания; метод репозитория без владельца перечислен поимённо
 * в {@code OwnerIsRequiredByAssignmentsTest}.
 *
 * <p>Зависит только от репозитория, не от {@code AssignmentService}:
 * {@code ProblemService} собирает список ответчиков, и сервис Заданий, зависящий
 * от {@code ProblemService}, замкнул бы кольцо бинов.
 */
@Component
public class AssignmentsOfProblem implements ProblemUsage {

    private final AssignmentRepository assignments;

    public AssignmentsOfProblem(AssignmentRepository assignments) {
        this.assignments = assignments;
    }

    @Override
    public Optional<String> of(ProblemId problem) {
        int count = assignments.countByProblem(problem);
        return count == 0 ? Optional.empty() : Optional.of("вошла в Задания (" + count + ")");
    }
}
