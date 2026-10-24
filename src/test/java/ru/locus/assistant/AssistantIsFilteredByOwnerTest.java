package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Разговоры, Реплики и строки хода — личный контур: каждый метод
 * {@link AssistantRepository} отвечает только своему владельцу (ADR-0027).
 *
 * <p>Изоляция — двумя учителями на каждом методе: форму «у метода есть
 * {@link UserId}» стережёт {@link OwnerIsRequiredByAssistantTest}, а здесь —
 * что владелец действительно стоит в запросе. Часть правил живёт в схеме
 * (составные ключи, частичный уникальный индекс), поэтому проверка идёт
 * на настоящей базе.
 */
class AssistantIsFilteredByOwnerTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Autowired
    private AssistantRepository repository;

    @Autowired
    private TestAccounts accounts;

    private UserId alice;
    private UserId bob;
    private AssistantConversationId alicesConversation;

    @BeforeEach
    void twoTeachers() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        alicesConversation = repository.createConversation(alice, NOW);
    }

    @Test
    void foreignConversationIsNotFound() {
        assertThat(repository.findConversation(alice, alicesConversation)).isPresent();
        assertThat(repository.findConversation(bob, alicesConversation)).isEmpty();
    }

    @Test
    void sessionIsRecordedOnlyByTheOwner() {
        repository.recordSession(bob, alicesConversation, "чужой-сеанс");
        assertThat(repository.findConversation(alice, alicesConversation).orElseThrow().hasSession()).isFalse();

        repository.recordSession(alice, alicesConversation, "свой-сеанс");
        assertThat(repository.findConversation(alice, alicesConversation).orElseThrow().session())
                .isEqualTo("свой-сеанс");
    }

    @Test
    void turnCannotBeStartedInAForeignConversation() {
        assertThatThrownBy(() -> repository.startTurn(bob, alicesConversation, "привет", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignTurnIsNotFoundAndNotListed() {
        AssistantTurnId turn = repository.startTurn(alice, alicesConversation, "привет", NOW);

        assertThat(repository.findTurn(alice, turn)).isPresent();
        assertThat(repository.findTurn(bob, turn)).isEmpty();
        assertThat(repository.turnsOf(alice, alicesConversation)).hasSize(1);
        assertThat(repository.turnsOf(bob, alicesConversation)).isEmpty();
    }

    @Test
    void foreignRunningTurnIsNotFound() {
        AssistantTurnId turn = repository.startTurn(alice, alicesConversation, "привет", NOW);

        assertThat(repository.findRunningTurn(alice)).map(AssistantTurn::id).contains(turn);
        assertThat(repository.findRunningTurn(bob)).isEmpty();
    }

    @Test
    void foreignConversationsAreNotListed() {
        repository.startTurn(alice, alicesConversation, "привет", NOW);

        assertThat(repository.conversationsOf(alice, 30)).extracting(ConversationSummary::id)
                .containsExactly(alicesConversation);
        assertThat(repository.conversationsOf(bob, 30)).isEmpty();
        assertThat(repository.latestConversation(alice)).map(ConversationSummary::id).contains(alicesConversation);
        assertThat(repository.latestConversation(bob)).isEmpty();
    }

    @Test
    void conversationsAreListedByTheirLastTurnAndOnlyWithTurns() {
        AssistantTurnId old = repository.startTurn(alice, alicesConversation, "первый разговор", NOW);
        repository.finishTurn(alice, old,
                new TurnOutcome(TurnState.DONE, "ответ", null, null, null, null, NOW.plusSeconds(1)));
        AssistantConversationId later = repository.createConversation(alice, NOW.plusSeconds(10));
        AssistantTurnId second = repository.startTurn(alice, later, "второй разговор", NOW.plusSeconds(10));
        repository.finishTurn(alice, second,
                new TurnOutcome(TurnState.DONE, "ответ", null, null, null, null, NOW.plusSeconds(11)));
        repository.createConversation(alice, NOW.plusSeconds(20));
        repository.startTurn(alice, alicesConversation, "продолжение первого", NOW.plusSeconds(30));

        assertThat(repository.conversationsOf(alice, 30))
                .extracting(ConversationSummary::id, ConversationSummary::firstPrompt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(alicesConversation, "первый разговор"),
                        org.assertj.core.groups.Tuple.tuple(later, "второй разговор"));
        assertThat(repository.conversationsOf(alice, 1)).hasSize(1);
    }

    @Test
    void stepsAreWrittenAndReadOnlyByTheOwner() {
        AssistantTurnId turn = repository.startTurn(alice, alicesConversation, "привет", NOW);

        repository.addStep(alice, turn, "mcp__locus__list_topics", "{}");
        repository.addStep(alice, turn, "mcp__locus__list_methods", "{}");
        assertThatThrownBy(() -> repository.addStep(bob, turn, "mcp__locus__find_problems", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.stepsOf(alice, turn))
                .extracting(TurnStep::position, TurnStep::toolName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "mcp__locus__list_topics"),
                        org.assertj.core.groups.Tuple.tuple(2, "mcp__locus__list_methods"));
        assertThat(repository.stepsOf(bob, turn)).isEmpty();
    }

    @Test
    void foreignTurnIsNotFinished() {
        AssistantTurnId turn = repository.startTurn(alice, alicesConversation, "привет", NOW);
        TurnOutcome done = new TurnOutcome(TurnState.DONE, "ответ", null, 10L, 2L, new BigDecimal("0.16"),
                NOW.plusSeconds(20));

        assertThat(repository.finishTurn(bob, turn, done)).isFalse();
        assertThat(repository.findTurn(alice, turn).orElseThrow().state()).isEqualTo(TurnState.RUNNING);

        assertThat(repository.finishTurn(alice, turn, done)).isTrue();
        AssistantTurn finished = repository.findTurn(alice, turn).orElseThrow();
        assertThat(finished.state()).isEqualTo(TurnState.DONE);
        assertThat(finished.answer()).isEqualTo("ответ");
        assertThat(finished.costUsd()).isEqualByComparingTo("0.16");
        assertThat(finished.inputTokens()).isEqualTo(10L);
        assertThat(finished.finishedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void outcomeIsWrittenOnlyOnce() {
        AssistantTurnId turn = repository.startTurn(alice, alicesConversation, "привет", NOW);
        repository.finishTurn(alice, turn,
                new TurnOutcome(TurnState.STOPPED, null, null, null, null, null, NOW.plusSeconds(1)));

        boolean second = repository.finishTurn(alice, turn,
                new TurnOutcome(TurnState.DONE, "поздно", null, null, null, null, NOW.plusSeconds(2)));

        assertThat(second).isFalse();
        assertThat(repository.findTurn(alice, turn).orElseThrow().state()).isEqualTo(TurnState.STOPPED);
    }

    @Test
    void secondRunningTurnOfTheSameTeacherIsRefusedButNotOfAnother() {
        repository.startTurn(alice, alicesConversation, "первая", NOW);
        AssistantConversationId another = repository.createConversation(alice, NOW);

        assertThatThrownBy(() -> repository.startTurn(alice, another, "вторая", NOW))
                .isInstanceOf(AssistantBusyException.class);

        AssistantConversationId bobs = repository.createConversation(bob, NOW);
        assertThat(repository.startTurn(bob, bobs, "своя", NOW)).isNotNull();
    }

    @Test
    void finishedTurnFreesTheTeacherForTheNextOne() {
        AssistantTurnId first = repository.startTurn(alice, alicesConversation, "первая", NOW);
        repository.finishTurn(alice, first,
                new TurnOutcome(TurnState.FAILED, null, "упало", null, null, null, NOW.plusSeconds(1)));

        assertThat(repository.startTurn(alice, alicesConversation, "вторая", NOW.plusSeconds(2))).isNotNull();
    }

    @Test
    void interruptRunningMarksEveryRunningTurnAndLeavesFinishedOnes() {
        AssistantTurnId finished = repository.startTurn(alice, alicesConversation, "первая", NOW);
        repository.finishTurn(alice, finished,
                new TurnOutcome(TurnState.DONE, "ответ", null, null, null, null, NOW.plusSeconds(1)));
        AssistantTurnId alicesRunning = repository.startTurn(alice, alicesConversation, "вторая", NOW);
        AssistantTurnId bobsRunning = repository.startTurn(bob, repository.createConversation(bob, NOW), "своя", NOW);

        int marked = repository.interruptRunning("перезапуск", NOW.plusSeconds(5));

        assertThat(marked).isGreaterThanOrEqualTo(2);
        assertThat(repository.findTurn(alice, alicesRunning).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(repository.findTurn(alice, alicesRunning).orElseThrow().cause()).isEqualTo("перезапуск");
        assertThat(repository.findTurn(bob, bobsRunning).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(repository.findTurn(alice, finished).orElseThrow().state()).isEqualTo(TurnState.DONE);
    }
}
