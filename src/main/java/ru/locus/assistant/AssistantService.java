package ru.locus.assistant;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Реплики Помощника от имени вошедшего учителя (ADR-0054; спека
 * {@code assistant-tools}; design.md agent-runner, Р3, Р9, Р10).
 *
 * <p>Владелец — всегда {@link CurrentUser}, не параметр: Разговоры и Реплики —
 * личный контур, чужие неотличимы от несуществующих (ADR-0027).
 *
 * <p>Начало Реплики не ждёт процесса: строка «работаю» заводится в базе —
 * там же база отклоняет вторую идущую Реплику учителя, — и после фиксации
 * Реплика отдаётся исполнителю. Именно после: исполнитель пишет исход
 * условным обновлением строки, которой до фиксации он бы не увидел.
 */
@Service
public class AssistantService {

    /** Предел длины запроса учителя в знаках. */
    public static final int PROMPT_LIMIT = 20_000;

    private final AssistantRepository repository;
    private final TurnRunner runner;
    private final RunningTurns running;
    private final CurrentUser currentUser;
    private final TransactionTemplate transactions;
    private final Clock clock;

    private volatile AssistantAvailability availability = AssistantAvailability.notChecked();

    public AssistantService(AssistantRepository repository, TurnRunner runner, RunningTurns running,
            CurrentUser currentUser, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.runner = runner;
        this.running = running;
        this.currentUser = currentUser;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Доступен ли Помощник — итог проверки при старте. */
    public AssistantAvailability availability() {
        return availability;
    }

    /** Итог проверки при старте ({@link AgentStartup}). */
    void availability(AssistantAvailability checked) {
        this.availability = checked;
    }

    /**
     * Начать Реплику вошедшего учителя и сразу вернуться.
     *
     * @param conversation Разговор, который Реплика продолжает; пусто — новый Разговор
     * @return Реплика в состоянии «работаю»
     * @throws AssistantUnavailableException          Помощник недоступен
     * @throws IllegalArgumentException               запрос пуст или длиннее {@link #PROMPT_LIMIT}
     * @throws AssistantConversationNotFoundException Разговора у учителя нет — в том числе чужой
     * @throws AssistantBusyException                 у учителя уже идёт Реплика
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public AssistantTurn startTurn(Optional<AssistantConversationId> conversation, String prompt) {
        AssistantAvailability current = availability;
        if (!current.available()) {
            throw new AssistantUnavailableException(current.cause());
        }
        String text = prompt == null ? "" : prompt.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Запрос Помощнику пуст");
        }
        if (text.codePointCount(0, text.length()) > PROMPT_LIMIT) {
            throw new IllegalArgumentException("Запрос Помощнику длиннее 20 000 знаков");
        }
        UserId owner = currentUser.id();
        TurnRunner.Job job = transactions.execute(status -> {
            AssistantConversation target = conversation
                    .map(id -> repository.findConversation(owner, id)
                            .orElseThrow(() -> new AssistantConversationNotFoundException(id)))
                    .orElseGet(() -> repository.findConversation(owner,
                            repository.createConversation(owner, clock.instant())).orElseThrow());
            AssistantTurnId turn = repository.startTurn(owner, target.id(), text, clock.instant());
            return new TurnRunner.Job(owner, turn, target.id(), text, target.session());
        });
        runner.start(job);
        return turn(job.turn());
    }

    /**
     * Остановить идущую Реплику вошедшего учителя. Завершившаяся остаётся
     * как есть: исход пишется один раз.
     *
     * @throws AssistantTurnNotFoundException Реплики у учителя нет — в том числе чужая
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public void stop(AssistantTurnId id) {
        AssistantTurn turn = turn(id);
        if (turn.state().running()) {
            running.stop(id);
        }
    }

    /**
     * Реплика вошедшего учителя.
     *
     * @throws AssistantTurnNotFoundException Реплики у учителя нет — в том числе чужая
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public AssistantTurn turn(AssistantTurnId id) {
        return repository.findTurn(currentUser.id(), id).orElseThrow(() -> new AssistantTurnNotFoundException(id));
    }

    /**
     * Строки хода Реплики вошедшего учителя в порядке вызова.
     *
     * @throws AssistantTurnNotFoundException Реплики у учителя нет — в том числе чужая
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public List<TurnStep> steps(AssistantTurnId id) {
        turn(id);
        return repository.stepsOf(currentUser.id(), id);
    }
}
