package ru.locus.warmup;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemUsage;

/**
 * Ответ области Разминок на вопрос библиотеки «использована ли Задача»:
 * с момента заведения Разминки — использована, и Задача замораживается
 * (ADR-0030, ADR-0061). Разметка Задачи — основание отметок, поставленных
 * на странице Разминки, и правка её задним числом меняла бы это основание.
 *
 * <p>Счёт идёт по Разминкам <b>всех</b> Учителей и по владельцу
 * не фильтруется, как у {@link ru.locus.assignment.AssignmentsOfProblem}:
 * спрашивает Администратор, у которого личного контура нет. Класс исключений
 * из ADR-0027, описанный в ADR-0036: наружу уходит <b>только число</b> —
 * ни Ученика, ни Учителя, ни самой Разминки; метод репозитория без владельца
 * перечислен поимённо в {@code OwnerIsRequiredByWarmupsTest}.
 *
 * <p>Зависит только от репозитория, не от {@link WarmupService}: тот зависит
 * от {@code ProblemService}, который собирает список ответчиков.
 */
@Component
public class WarmupsOfProblem implements ProblemUsage {

    private final WarmupRepository warmups;

    public WarmupsOfProblem(WarmupRepository warmups) {
        this.warmups = warmups;
    }

    @Override
    public Optional<String> of(ProblemId problem) {
        int count = warmups.countByProblem(problem);
        return count == 0 ? Optional.empty() : Optional.of("вошла в Разминки (" + count + ")");
    }
}
