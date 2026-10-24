package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.problem.AssemblyDraft;
import ru.locus.problem.AssemblyDraftService;
import ru.locus.user.Role;

/**
 * Какой Разговор показать и что делать с файлом реплики (спека
 * {@code assistant-chat}; design.md assistant-chat, Р3, Р5) — на сервисе,
 * тест-двойнике команды и {@link TestClock}: «сегодня» и «вчера» задаются
 * сдвигом часов, под которым начата Реплика.
 */
class AssistantConversationsTest extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private AssistantService service;
    @Autowired
    private AssistantRepository repository;
    @Autowired
    private AssemblyDraftService drafts;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private TestClock clock;

    @AfterEach
    void reset() {
        clock.reset();
        LoggedIn.nobody();
    }

    /** Сценарий «Тот же день». */
    @Test
    void todaysConversationIsShownAgain() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        AssistantTurn turn = finished(service.startTurn(Optional.empty(), "ответ"));

        assertThat(service.conversation(Optional.empty(), false))
                .map(AssistantConversation::id).contains(turn.conversation());
    }

    /** Сценарий «Новый день». */
    @Test
    void yesterdaysConversationIsNotShown() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        clock.shift(Duration.ofDays(-1));
        AssistantTurn yesterday = finished(service.startTurn(Optional.empty(), "ответ"));
        clock.reset();

        assertThat(service.conversation(Optional.empty(), false)).isEmpty();
        assertThat(service.conversations()).extracting(ConversationSummary::id)
                .containsExactly(yesterday.conversation());
    }

    /** Разговор, продолженный сегодня, — сегодняшний, хоть и заведён вчера. */
    @Test
    void conversationContinuedTodayIsTodays() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        clock.shift(Duration.ofDays(-1));
        AssistantTurn yesterday = finished(service.startTurn(Optional.empty(), "ответ"));
        clock.reset();
        finished(service.startTurn(Optional.of(yesterday.conversation()), "ответ ещё раз"));

        assertThat(service.conversation(Optional.empty(), false))
                .map(AssistantConversation::id).contains(yesterday.conversation());
    }

    @Test
    void freshPageIsEmptyEvenWithTodaysConversation() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        finished(service.startTurn(Optional.empty(), "ответ"));

        assertThat(service.conversation(Optional.empty(), true)).isEmpty();
    }

    @Test
    void conversationWithTheRunningTurnIsShownFirst() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        clock.shift(Duration.ofDays(-1));
        AssistantTurn running = service.startTurn(Optional.empty(), "зависание");
        clock.reset();

        assertThat(service.runningTurn()).map(AssistantTurn::id).contains(running.id());
        assertThat(service.conversation(Optional.empty(), false))
                .map(AssistantConversation::id).contains(running.conversation());
        service.stop(running.id());
        finished(running);
    }

    @Test
    void namedForeignConversationIsNotFound() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        AssistantTurn alices = finished(service.startTurn(Optional.empty(), "ответ"));
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.conversation(Optional.of(alices.conversation()), false))
                .isInstanceOf(AssistantConversationNotFoundException.class);
        assertThat(service.conversations()).isEmpty();
        assertThat(service.turns(alices.conversation())).isEmpty();
    }

    /** Сценарий «Снимок листа». */
    @Test
    void attachedPhotoBecomesTheAdministratorsDraftAndIsNamedInThePrompt() throws IOException {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        AssistantTurn turn = finished(service.startTurn(Optional.empty(), "ответ: что на листе?",
                Optional.of(new TurnAttachment("лист.png", new ByteArrayInputStream(png())))));

        AssemblyDraft draft = drafts.own().getFirst();
        assertThat(draft.originalName()).isEqualTo("лист.png");
        assertThat(turn.prompt()).isEqualTo("ответ: что на листе?\n\nЗагружен черновик сборки "
                + draft.id().value() + " «лист.png», страниц: 1.");
    }

    /** Сценарий «Только файл». */
    @Test
    void fileAloneIsAPrompt() throws IOException {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        AssistantTurn turn = finished(service.startTurn(Optional.empty(), "  ",
                Optional.of(new TurnAttachment("лист.png", new ByteArrayInputStream(png())))));

        assertThat(turn.prompt()).startsWith("Загружен черновик сборки ");
    }

    /** Сценарии «Пустой запрос» и «Неподходящий файл». */
    @Test
    void emptyPromptAndUnsuitableFileStartNoTurn() {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(() -> service.startTurn(Optional.empty(), " ", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("пуст");
        assertThatThrownBy(() -> service.startTurn(Optional.empty(), "ответ", Optional.of(
                new TurnAttachment("заметки.txt", new ByteArrayInputStream("текст".getBytes())))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JPEG и PNG");

        assertThat(service.conversations()).isEmpty();
        assertThat(service.runningTurn()).isEmpty();
    }

    @Test
    void fileIsNotTakenWhileAnotherTurnRuns() throws IOException {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));
        AssistantTurn running = service.startTurn(Optional.empty(), "зависание");

        assertThatThrownBy(() -> service.startTurn(Optional.empty(), "ответ",
                Optional.of(new TurnAttachment("лист.png", new ByteArrayInputStream(png())))))
                .isInstanceOf(AssistantBusyException.class);
        assertThat(drafts.own()).isEmpty();

        service.stop(running.id());
        finished(running);
    }

    private AssistantTurn finished(AssistantTurn turn) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            AssistantTurn current = repository.findTurn(turn.owner(), turn.id()).orElseThrow();
            if (!current.state().running()) {
                return current;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Реплика " + turn.id().value() + " не завершилась за " + PATIENCE);
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
