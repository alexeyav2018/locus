package ru.locus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Часы для тестов: системные, но со сдвигом, который тест ставит и снимает.
 *
 * Подменяют бин {@link Clock} из {@code TimeConfiguration} на всём тестовом
 * контексте ({@code @Primary}), а не в одном тесте через {@code @MockitoBean}:
 * подмена бина в одном классе поднимает второй контекст Spring на весь
 * прогон, а этот компонент, как {@link TestLibrary}, попадает в единственный
 * контекст обычным сканированием.
 *
 * Сдвиг — состояние, общее для всех тестов прогона, поэтому поставивший его
 * тест обязан снять его в {@code @AfterEach}: забытый сдвиг тихо сделает
 * несданными Задания соседнего теста.
 */
@Component
@Primary
public class TestClock extends Clock {

    private final Clock system = Clock.systemDefaultZone();

    private volatile Duration shift = Duration.ZERO;

    /** Сдвинуть часы: положительная длительность — в будущее. */
    public void shift(Duration duration) {
        this.shift = duration;
    }

    /** Вернуть системное время. */
    public void reset() {
        this.shift = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return system.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("Тестовые часы живут в системной зоне");
    }

    @Override
    public Instant instant() {
        return system.instant().plus(shift);
    }
}
