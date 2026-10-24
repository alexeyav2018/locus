package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Как состояние Реплики и строка хода показываются учителю
 * (design.md assistant-chat, Р8): тон значка у каждого из семи состояний
 * и имя инструмента без приставки сервера Локуса.
 */
class TurnStateTest {

    @Test
    void everyStateHasAToneOfTheStylesheet() {
        assertThat(TurnState.RUNNING.tone()).isEqualTo("info");
        assertThat(TurnState.DONE.tone()).isEqualTo("ok");
        assertThat(TurnState.STOPPED.tone()).isEqualTo("warn");
        assertThat(TurnState.STEP_LIMIT.tone()).isEqualTo("warn");
        assertThat(TurnState.FAILED.tone()).isEqualTo("bad");
        assertThat(TurnState.TIMED_OUT.tone()).isEqualTo("bad");
        assertThat(TurnState.INTERRUPTED.tone()).isEqualTo("bad");
    }

    @Test
    void locusToolIsShownWithoutThePrefix() {
        assertThat(new TurnStep(1, "mcp__locus__list_topics", "{}").tool()).isEqualTo("list_topics");
    }

    @Test
    void foreignToolIsShownAsNamed() {
        assertThat(new TurnStep(1, "mcp__other__whoami", "{}").tool()).isEqualTo("mcp__other__whoami");
    }
}
