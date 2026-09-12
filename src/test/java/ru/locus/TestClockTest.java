package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Задача 1.2: часы приложения — один бин, и в тестах его можно сдвинуть.
 *
 * Проверяется, что сервисы получают именно {@link TestClock}, а не системные
 * часы из {@code TimeConfiguration}: иначе тест «сдвиг времени меняет
 * "не сдано"» сдвигал бы часы, на которые никто не смотрит.
 */
class TestClockTest extends IntegrationTest {

    @Autowired
    private Clock clock;

    @Autowired
    private TestClock testClock;

    @AfterEach
    void resetClock() {
        testClock.reset();
    }

    @Test
    void theApplicationClockIsTheTestClock() {
        assertThat(clock).isSameAs(testClock);
    }

    @Test
    void shiftMovesTodayAndResetReturnsIt() {
        LocalDate today = LocalDate.now();

        testClock.shift(Duration.ofDays(3));
        assertThat(LocalDate.now(clock)).isEqualTo(today.plusDays(3));

        testClock.reset();
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.now());
    }
}
