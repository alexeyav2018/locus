package ru.locus.work;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.student.StudentId;
import ru.locus.student.StudentUsage;
import ru.locus.user.CurrentUser;

/**
 * Ответ области Работ на вопрос «ссылается ли что-нибудь на Ученика»:
 * Ученик, от которого приняты Работы, не удаляется (ADR-0035).
 *
 * <p>Ученика у Работы нет — он у Задания, и счёт идёт соединением
 * с Заданиями; потому Ученик с Работой всегда имеет и Задание, и отказ
 * в удалении назовёт обе причины: {@code StudentService} собирает ответы
 * всех ответчиков.
 *
 * <p>Владелец — у {@link CurrentUser}, как у
 * {@link ru.locus.assignment.AssignmentsOfStudent}; зависит только
 * от репозитория и {@link CurrentUser}, не от {@link StudentWorkService}:
 * тот зависит от {@code StudentService}, который собирает список
 * ответчиков.
 */
@Component
public class WorksOfStudent implements StudentUsage {

    private final StudentWorkRepository works;
    private final CurrentUser currentUser;

    public WorksOfStudent(StudentWorkRepository works, CurrentUser currentUser) {
        this.works = works;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<String> of(StudentId student) {
        int count = works.countByStudent(currentUser.id(), student);
        return count == 0 ? Optional.empty() : Optional.of("принято Работ (" + count + ")");
    }
}
