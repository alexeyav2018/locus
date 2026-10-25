package ru.locus.problem;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.Addresses;
import ru.locus.assistant.AwaitingReview;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;

/**
 * Ответ области Задач на вопрос {@link AwaitingReview}: у Администратора
 * в очереди вычитки — его Черновики Задачи (problem-intake, design.md, Р6).
 * Учителю без роли Администратора черновиков не завести, и ссылки
 * у него нет.
 *
 * <p>Роль читается ровно для одного — показывать ли ссылку; права
 * на сами черновики проверяет {@link ProblemDraftService}.
 */
@Component
public class ProblemDraftQueue implements AwaitingReview {

    private final ProblemDraftService intake;
    private final CurrentUser currentUser;

    public ProblemDraftQueue(ProblemDraftService intake, CurrentUser currentUser) {
        this.intake = intake;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Queue> awaiting() {
        return currentUser.loggedIn()
                .filter(user -> user.hasRole(Role.ADMINISTRATOR))
                .map(user -> new Queue("Вычитать черновики", Addresses.PROBLEM_INTAKE, intake.count()));
    }
}
