package ru.locus.warmup;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.student.StudentId;
import ru.locus.student.StudentUsage;
import ru.locus.user.CurrentUser;

/**
 * Ответ области Разминок на вопрос «ссылается ли что-нибудь на Ученика»:
 * участник Разминки не удаляется (ADR-0035, ADR-0061) — его итоги у доски
 * такая же история, как Работы. Группа, которой проводили Разминку,
 * удаляется по-прежнему: Разминка помнит её имя текстом, а участников —
 * поимённо.
 *
 * <p>Владелец — у {@link CurrentUser}, как у
 * {@link ru.locus.work.WorksOfStudent}; зависит только от репозитория
 * и {@link CurrentUser}, не от {@link WarmupService}: тот зависит
 * от {@code StudentService}, который собирает список ответчиков.
 */
@Component
public class WarmupsOfStudent implements StudentUsage {

    private final WarmupRepository warmups;
    private final CurrentUser currentUser;

    public WarmupsOfStudent(WarmupRepository warmups, CurrentUser currentUser) {
        this.warmups = warmups;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<String> of(StudentId student) {
        int count = warmups.countByStudent(currentUser.id(), student);
        return count == 0 ? Optional.empty() : Optional.of("участвовал в Разминках (" + count + ")");
    }
}
