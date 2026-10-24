package ru.locus.assistant;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.user.UserId;

/**
 * Хранение Разговоров, Реплик и строк хода — репозиторий ЛИЧНОГО контура
 * (ADR-0054; design.md agent-runner, Р1, Р3).
 *
 * <p>У каждого публичного метода есть владелец {@link UserId}, и в каждом SQL
 * стоит {@code user_id} (ADR-0027): чужой Разговор и чужая Реплика неотличимы
 * от несуществующих — чтение отдаёт пусто, правка не трогает ни строки.
 * Привязать Реплику к чужому Разговору или строку хода к чужой Реплике нельзя
 * и в обход сервиса: ключи между таблицами составные, на {@code (id, user_id)}.
 *
 * <p>Исключение одно — {@link #interruptRunning}: уборка при старте класса
 * ADR-0036. После перезапуска процессов Помощника нет ни у кого, и каждая
 * Реплика в «работаю» помечается прерванной, чья бы она ни была; метод только
 * меняет состояние и наружу ничего не отдаёт. Перечислен поимённо
 * в {@code OwnerIsRequiredByAssistantTest}.
 */
@Repository
public class AssistantRepository {

    private static final String TURN_COLUMNS = """
            select id, user_id, conversation_id, prompt, state, answer, cause,
                   input_tokens, output_tokens, cost_usd, started_at, finished_at
            from assistant_turn
            """;

    private final JdbcClient database;

    public AssistantRepository(JdbcClient database) {
        this.database = database;
    }

    /** Заводит Разговор владельца, пока без сеанса. */
    public AssistantConversationId createConversation(UserId owner, Instant createdAt) {
        Long id = database.sql("""
                        insert into assistant_conversation (user_id, created_at)
                        values (?, ?)
                        returning id
                        """)
                .params(owner.value(), Timestamp.from(createdAt))
                .query(Long.class)
                .single();
        return new AssistantConversationId(id);
    }

    /** Разговор владельца; чужой и несуществующий — пусто. */
    public Optional<AssistantConversation> findConversation(UserId owner, AssistantConversationId id) {
        return database.sql("""
                        select id, user_id, session_id, created_at
                        from assistant_conversation
                        where user_id = ? and id = ?
                        """)
                .params(owner.value(), id.value())
                .query((rs, rowNum) -> new AssistantConversation(
                        new AssistantConversationId(rs.getLong("id")),
                        new UserId(rs.getLong("user_id")),
                        rs.getString("session_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /**
     * Записывает сеанс Claude Code в Разговор владельца — тот, что процесс
     * назвал первым событием Реплики. Чужой Разговор не трогает.
     */
    public void recordSession(UserId owner, AssistantConversationId id, String session) {
        database.sql("update assistant_conversation set session_id = ? where user_id = ? and id = ?")
                .params(session, owner.value(), id.value())
                .update();
    }

    /**
     * Заводит Реплику владельца в его Разговоре в состоянии «работаю».
     *
     * @throws AssistantBusyException у владельца уже идёт Реплика — отказ
     *                                частичного уникального индекса
     * @throws org.springframework.dao.DataIntegrityViolationException Разговор
     *                                чужой: составной ключ строку не примет
     */
    public AssistantTurnId startTurn(UserId owner, AssistantConversationId conversation, String prompt,
            Instant startedAt) {
        try {
            Long id = database.sql("""
                            insert into assistant_turn (user_id, conversation_id, prompt, state, started_at)
                            values (?, ?, ?, 'RUNNING', ?)
                            returning id
                            """)
                    .params(owner.value(), conversation.value(), prompt, Timestamp.from(startedAt))
                    .query(Long.class)
                    .single();
            return new AssistantTurnId(id);
        } catch (DuplicateKeyException busy) {
            throw new AssistantBusyException();
        }
    }

    /** Реплика владельца; чужая и несуществующая — пусто. */
    public Optional<AssistantTurn> findTurn(UserId owner, AssistantTurnId id) {
        return database.sql(TURN_COLUMNS + "where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .query(AssistantRepository::turn)
                .optional();
    }

    /** Реплики Разговора владельца в порядке начала; у чужого — пусто. */
    public List<AssistantTurn> turnsOf(UserId owner, AssistantConversationId conversation) {
        return database.sql(TURN_COLUMNS + "where user_id = ? and conversation_id = ? order by id")
                .params(owner.value(), conversation.value())
                .query(AssistantRepository::turn)
                .list();
    }

    /**
     * Идущая Реплика владельца — их не больше одной (частичный уникальный
     * индекс); чужая не видна.
     */
    public Optional<AssistantTurn> findRunningTurn(UserId owner) {
        return database.sql(TURN_COLUMNS + "where user_id = ? and state = 'RUNNING'")
                .param(owner.value())
                .query(AssistantRepository::turn)
                .optional();
    }

    /**
     * Разговоры владельца, в которых есть Реплики, — новые по последней
     * Реплике сверху. Разговор без Реплик (первая отклонена) не попадает:
     * показывать в нём нечего.
     */
    public List<ConversationSummary> conversationsOf(UserId owner, int limit) {
        return database.sql("""
                        select c.id, max(t.started_at) as last_turn_at,
                               (select f.prompt from assistant_turn f
                                where f.user_id = c.user_id and f.conversation_id = c.id
                                order by f.id limit 1) as first_prompt
                        from assistant_conversation c
                        join assistant_turn t on t.conversation_id = c.id and t.user_id = c.user_id
                        where c.user_id = ?
                        group by c.id, c.user_id
                        order by last_turn_at desc, c.id desc
                        limit ?
                        """)
                .params(owner.value(), limit)
                .query((rs, rowNum) -> new ConversationSummary(
                        new AssistantConversationId(rs.getLong("id")),
                        rs.getTimestamp("last_turn_at").toInstant(),
                        rs.getString("first_prompt")))
                .list();
    }

    /** Последний Разговор владельца с Репликами — тот, где говорили позже всего. */
    public Optional<ConversationSummary> latestConversation(UserId owner) {
        return conversationsOf(owner, 1).stream().findFirst();
    }

    /**
     * Дописывает строку хода в Реплику владельца следующим номером. Строки
     * пишет один поток Реплики, по очереди, поэтому номер считается выборкой,
     * а не последовательностью. Чужую Реплику база не примет.
     */
    public void addStep(UserId owner, AssistantTurnId turn, String toolName, String arguments) {
        database.sql("""
                        insert into assistant_turn_step (turn_id, user_id, position, tool_name, arguments)
                        select ?, ?, coalesce(max(position), 0) + 1, ?, ?
                        from assistant_turn_step
                        where turn_id = ? and user_id = ?
                        """)
                .params(turn.value(), owner.value(), toolName, arguments, turn.value(), owner.value())
                .update();
    }

    /** Строки хода Реплики владельца в порядке вызова; у чужой — пусто. */
    public List<TurnStep> stepsOf(UserId owner, AssistantTurnId turn) {
        return database.sql("""
                        select position, tool_name, arguments
                        from assistant_turn_step
                        where user_id = ? and turn_id = ?
                        order by position
                        """)
                .params(owner.value(), turn.value())
                .query((rs, rowNum) -> new TurnStep(rs.getInt("position"), rs.getString("tool_name"),
                        rs.getString("arguments")))
                .list();
    }

    /**
     * Записывает исход идущей Реплики владельца. Реплику, уже пришедшую
     * в конечное состояние, и чужую не трогает: исход пишется один раз.
     *
     * @return записан ли исход
     */
    public boolean finishTurn(UserId owner, AssistantTurnId turn, TurnOutcome outcome) {
        int updated = database.sql("""
                        update assistant_turn
                        set state = ?, answer = ?, cause = ?, input_tokens = ?, output_tokens = ?,
                            cost_usd = ?, finished_at = ?
                        where user_id = ? and id = ? and state = 'RUNNING'
                        """)
                .params(outcome.state().name(), outcome.answer(), outcome.cause(), outcome.inputTokens(),
                        outcome.outputTokens(), outcome.costUsd(), Timestamp.from(outcome.finishedAt()),
                        owner.value(), turn.value())
                .update();
        return updated == 1;
    }

    /**
     * Уборка при старте, без владельца (класс ADR-0036): каждая Реплика
     * в «работаю» становится «прервано перезапуском» с причиной. Наружу
     * ничего не отдаёт, кроме числа помеченных.
     */
    public int interruptRunning(String cause, Instant at) {
        return database.sql("""
                        update assistant_turn
                        set state = 'INTERRUPTED', cause = ?, finished_at = ?
                        where state = 'RUNNING'
                        """)
                .params(cause, Timestamp.from(at))
                .update();
    }

    private static AssistantTurn turn(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new AssistantTurn(
                new AssistantTurnId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                new AssistantConversationId(rs.getLong("conversation_id")),
                rs.getString("prompt"),
                TurnState.valueOf(rs.getString("state")),
                rs.getString("answer"),
                rs.getString("cause"),
                rs.getObject("input_tokens", Long.class),
                rs.getObject("output_tokens", Long.class),
                rs.getObject("cost_usd", BigDecimal.class),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
