package ru.locus.work;

import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentWork;
import ru.locus.user.CurrentUser;

/**
 * Ответ области Работ на вопрос Заданий «есть ли по этим Заданиям
 * Работа»: Задание с Работой хотя бы по одной Задаче — сдано и не
 * удаляется (ADR-0037).
 *
 * <p>Работа считается с момента приёма, не с момента вердикта:
 * непроверенная Работа — тоже Работа, и Задание с ней не «не сдано»
 * (ADR-0038). Вердикт здесь не спрашивается вовсе.
 *
 * <p>Владелец — у {@link CurrentUser}: вопрос задаёт личная область
 * личной же от имени вошедшего Учителя, как у
 * {@link ru.locus.assignment.AssignmentsOfStudent}. Чужие Задания
 * вошедший и не найдёт, так что фильтр по владельцу не сужает ответ,
 * а держит форму: у метода репозитория есть владелец (ADR-0027).
 *
 * <p>Зависит только от репозитория и {@link CurrentUser}, не от
 * {@link StudentWorkService}: тот зависит от {@code AssignmentService},
 * который собирает список ответчиков, — зависимость от сервиса дала бы
 * цикл бинов.
 */
@Component
public class WorksOfAssignment implements AssignmentWork {

    private final StudentWorkRepository works;
    private final CurrentUser currentUser;

    public WorksOfAssignment(StudentWorkRepository works, CurrentUser currentUser) {
        this.works = works;
        this.currentUser = currentUser;
    }

    @Override
    public Set<AssignmentId> withWork(Collection<AssignmentId> assignments) {
        return works.assignmentsWithWork(currentUser.id(), assignments);
    }
}
