package ru.locus.assignment;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.student.StudentId;
import ru.locus.student.StudentUsage;
import ru.locus.user.CurrentUser;

/**
 * Ответ области Заданий на вопрос «ссылается ли что-нибудь на Ученика»:
 * Ученик с выданным Заданием не удаляется (ADR-0035).
 *
 * <p>В отличие от {@link AssignmentsOfProblem}, владелец здесь есть:
 * вопрос задаёт личная область личной же, спрашивает {@code StudentService}
 * от имени вошедшего Учителя, и владелец берётся у {@link CurrentUser} —
 * ровно так, как ADR-0036 отделяет этот вопрос от класса «без владельца».
 * Чужого Ученика вошедший и не найдёт, так что счёт по владельцу
 * не сужает ответ, а лишь держит форму: у метода репозитория есть владелец.
 *
 * <p>Зависит только от репозитория и {@link CurrentUser}, не от
 * {@code AssignmentService}: {@code StudentService} собирает список
 * ответчиков, а сервис Заданий зависит от {@code StudentService}.
 */
@Component
public class AssignmentsOfStudent implements StudentUsage {

    private final AssignmentRepository assignments;
    private final CurrentUser currentUser;

    public AssignmentsOfStudent(AssignmentRepository assignments, CurrentUser currentUser) {
        this.assignments = assignments;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<String> of(StudentId student) {
        int count = assignments.countByStudent(currentUser.id(), student);
        return count == 0 ? Optional.empty() : Optional.of("выдано Заданий (" + count + ")");
    }
}
