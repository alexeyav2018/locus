package ru.locus.assistant;

import org.junit.jupiter.api.Test;
import ru.locus.TestClock;
import ru.locus.user.UserId;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Токен Реплики живёт от выпуска до отзыва или истечения, и только
 * в памяти (ADR-0057). Часы — {@link TestClock} напрямую, без Spring:
 * хранилищу поднятый контекст не нужен.
 */
class TurnTokensTest {

    private final TestClock clock = new TestClock();
    private final TurnTokens tokens = new TurnTokens(clock);
    private final UserId teacher = new UserId(7);

    @Test
    void issuedTokenResolvesToItsUser() {
        TurnToken token = tokens.issue(teacher, Duration.ofMinutes(5));

        assertThat(tokens.resolve(token.value())).contains(teacher);
    }

    @Test
    void everyTokenIsItsOwn() {
        UserId other = new UserId(8);
        TurnToken first = tokens.issue(teacher, Duration.ofMinutes(5));
        TurnToken second = tokens.issue(other, Duration.ofMinutes(5));

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(tokens.resolve(first.value())).contains(teacher);
        assertThat(tokens.resolve(second.value())).contains(other);
    }

    @Test
    void revokedTokenResolvesToNothing() {
        TurnToken token = tokens.issue(teacher, Duration.ofMinutes(5));
        tokens.revoke(token);

        assertThat(tokens.resolve(token.value())).isEmpty();
    }

    @Test
    void expiredTokenResolvesToNothingAndIsForgotten() {
        TurnToken token = tokens.issue(teacher, Duration.ofMinutes(1));
        clock.shift(Duration.ofMinutes(2));

        assertThat(tokens.resolve(token.value())).isEmpty();

        clock.reset();
        assertThat(tokens.resolve(token.value()))
                .as("истёкший удалён при разрешении, а не ожил с возвратом часов")
                .isEmpty();
    }

    @Test
    void unknownTokenResolvesToNothing() {
        assertThat(tokens.resolve("нет-такого")).isEmpty();
        assertThat(tokens.resolve(null)).isEmpty();
        tokens.revoke(new TurnToken("нет-такого"));
    }

    @Test
    void tokenDoesNotRevealItsValue() {
        TurnToken token = tokens.issue(teacher, Duration.ofMinutes(5));

        assertThat(token.toString()).doesNotContain(token.value());
        assertThatThrownBy(() -> new TurnToken(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifetimeMustBePositive() {
        assertThatThrownBy(() -> tokens.issue(teacher, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue(teacher, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue(null, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
