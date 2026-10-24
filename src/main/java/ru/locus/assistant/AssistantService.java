package ru.locus.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
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

    /** Сколько прежних Разговоров видно на странице (design.md assistant-chat, Р10). */
    public static final int CONVERSATIONS_SHOWN = 30;

    private final AssistantRepository repository;
    private final TurnRunner runner;
    private final RunningTurns running;
    private final CurrentUser currentUser;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectProvider<TurnAttachments> attachments;

    private volatile AssistantAvailability availability = AssistantAvailability.notChecked();

    public AssistantService(AssistantRepository repository, TurnRunner runner, RunningTurns running,
            CurrentUser currentUser, TransactionTemplate transactions, Clock clock,
            ObjectProvider<TurnAttachments> attachments) {
        this.repository = repository;
        this.runner = runner;
        this.running = running;
        this.currentUser = currentUser;
        this.transactions = transactions;
        this.clock = clock;
        this.attachments = attachments;
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
     * Начать Реплику вошедшего учителя без файла и сразу вернуться.
     *
     * @see #startTurn(Optional, String, Optional)
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public AssistantTurn startTurn(Optional<AssistantConversationId> conversation, String prompt) {
        return startTurn(conversation, prompt, Optional.empty());
    }

    /**
     * Начать Реплику вошедшего учителя и сразу вернуться.
     *
     * <p>Приложенный файл принимает ответчик {@link TurnAttachments}
     * (design.md assistant-chat, Р5), и запрос Помощнику — текст учителя
     * и строка ответчика через пустую строку; запрос может состоять из одного
     * файла. Отказы, не требующие файла, — недоступность, пустота, идущая
     * Реплика — проверяются до приёма: отклонённая Реплика не оставляет
     * черновика. Гонка двух отправок всё же может его оставить — его уберёт
     * попутная уборка черновиков по сроку.
     *
     * @param conversation Разговор, который Реплика продолжает; пусто — новый Разговор
     * @param attachment   файл к запросу; пусто — без файла
     * @return Реплика в состоянии «работаю»
     * @throws AssistantUnavailableException          Помощник недоступен
     * @throws IllegalArgumentException               запрос пуст и файла нет, запрос длиннее
     *                                                {@link #PROMPT_LIMIT}, файл не подходит
     * @throws AssistantConversationNotFoundException Разговора у учителя нет — в том числе чужой
     * @throws AssistantBusyException                 у учителя уже идёт Реплика
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public AssistantTurn startTurn(Optional<AssistantConversationId> conversation, String prompt,
            Optional<TurnAttachment> attachment) {
        AssistantAvailability current = availability;
        if (!current.available()) {
            throw new AssistantUnavailableException(current.cause());
        }
        String text = prompt == null ? "" : prompt.strip();
        if (text.isEmpty() && attachment.isEmpty()) {
            throw new IllegalArgumentException("Запрос Помощнику пуст");
        }
        UserId owner = currentUser.id();
        if (attachment.isPresent()) {
            if (repository.findRunningTurn(owner).isPresent()) {
                throw new AssistantBusyException();
            }
            TurnAttachments receiver = attachments.getIfUnique();
            if (receiver == null) {
                throw new IllegalArgumentException("Файлы Помощнику не принимаются");
            }
            String note = receiver.attach(attachment.get());
            text = text.isEmpty() ? note : text + "\n\n" + note;
        }
        if (text.codePointCount(0, text.length()) > PROMPT_LIMIT) {
            throw new IllegalArgumentException("Запрос Помощнику длиннее 20 000 знаков");
        }
        String request = text;
        TurnRunner.Job job = transactions.execute(status -> {
            AssistantConversation target = conversation
                    .map(id -> repository.findConversation(owner, id)
                            .orElseThrow(() -> new AssistantConversationNotFoundException(id)))
                    .orElseGet(() -> repository.findConversation(owner,
                            repository.createConversation(owner, clock.instant())).orElseThrow());
            AssistantTurnId turn = repository.startTurn(owner, target.id(), request, clock.instant());
            return new TurnRunner.Job(owner, turn, target.id(), request, target.session());
        });
        runner.start(job);
        return turn(job.turn());
    }

    /** Идущая Реплика вошедшего учителя, если она есть, — в любом его Разговоре. */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public Optional<AssistantTurn> runningTurn() {
        return repository.findRunningTurn(currentUser.id());
    }

    /**
     * Разговор, который показать на странице (design.md assistant-chat, Р3).
     * Названный — он, чужой и несуществующий — отказ. Без названного:
     * {@code fresh} — пусто (кнопка «Новый разговор»); иначе Разговор
     * с идущей Репликой, иначе последний, если его последняя Реплика начата
     * сегодня по часам приложения; иначе пусто — первая реплика нового дня
     * начинает новый Разговор.
     *
     * @throws AssistantConversationNotFoundException названного Разговора у учителя нет
     */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public Optional<AssistantConversation> conversation(Optional<AssistantConversationId> named, boolean fresh) {
        UserId owner = currentUser.id();
        if (named.isPresent()) {
            AssistantConversationId id = named.get();
            return Optional.of(repository.findConversation(owner, id)
                    .orElseThrow(() -> new AssistantConversationNotFoundException(id)));
        }
        if (fresh) {
            return Optional.empty();
        }
        Optional<AssistantConversationId> shown = repository.findRunningTurn(owner)
                .map(AssistantTurn::conversation)
                .or(() -> repository.latestConversation(owner)
                        .filter(latest -> LocalDate.ofInstant(latest.lastTurnAt(), clock.getZone())
                                .equals(LocalDate.now(clock)))
                        .map(ConversationSummary::id));
        return shown.flatMap(id -> repository.findConversation(owner, id));
    }

    /** Прежние Разговоры вошедшего учителя, новые сверху, не больше {@link #CONVERSATIONS_SHOWN}. */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public List<ConversationSummary> conversations() {
        return repository.conversationsOf(currentUser.id(), CONVERSATIONS_SHOWN);
    }

    /** Реплики Разговора вошедшего учителя в порядке начала; у чужого — пусто. */
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMINISTRATOR')")
    public List<AssistantTurn> turns(AssistantConversationId conversation) {
        return repository.turnsOf(currentUser.id(), conversation);
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
