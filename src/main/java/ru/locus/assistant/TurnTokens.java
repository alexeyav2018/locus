package ru.locus.assistant;

import org.springframework.stereotype.Component;
import ru.locus.user.UserId;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Хранилище токенов Реплики — выпуск, разрешение и отзыв (ADR-0057;
 * design.md, Р3).
 *
 * <p>Живёт в памяти и в базу не пишет — намеренно: токен должен умереть
 * вместе с процессом, а запись в базе пережила бы перезапуск и дала бы
 * Реплике, прерванной остановкой, действовать после подъёма. По той же
 * причине нет уборки по расписанию — фоновых процессов в системе нет
 * (ADR-0014): истёкший токен отклоняется при разрешении и тогда же
 * удаляется, отозванный удаляется при отзыве.
 *
 * <p>Значение — 32 случайных байта в base64url: его нельзя угадать,
 * и в заголовке оно не требует кодирования. Сравнение идёт поиском
 * в хранилище, а не разбором значения: токен не несёт ничего, кроме
 * случайности.
 *
 * <p>Выпускать токен на настоящую Реплику будет её сервис
 * ({@code agent-runner}); здесь — выпуск, проверка и отзыв, которыми
 * пользуются {@link TurnTokenFilter} и тест-двойник клиента.
 */
@Component
public class TurnTokens {

    private static final int VALUE_BYTES = 32;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Issued> issued = new ConcurrentHashMap<>();

    public TurnTokens(Clock clock) {
        this.clock = clock;
    }

    /**
     * Выпустить токен на Пользователя: с этого момента и до истечения
     * срока вызов с ним идёт от его имени.
     *
     * @param lifetime срок жизни, положительный; задаёт выпускающий
     */
    public TurnToken issue(UserId user, Duration lifetime) {
        if (user == null) {
            throw new IllegalArgumentException("Токен Реплики выпускается на Пользователя");
        }
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("Срок токена Реплики должен быть положительным");
        }
        byte[] bytes = new byte[VALUE_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        issued.put(value, new Issued(user, clock.instant().plus(lifetime)));
        return new TurnToken(value);
    }

    /**
     * Пользователь, на которого выпущен живой токен с таким значением;
     * пусто для неизвестного, отозванного и истёкшего. Истёкший при этом
     * удаляется: второго разрешения ему не понадобится.
     */
    public Optional<UserId> resolve(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Issued found = issued.get(value);
        if (found == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(found.expiresAt())) {
            issued.remove(value, found);
            return Optional.empty();
        }
        return Optional.of(found.user());
    }

    /** Отозвать токен — по концу Реплики. Неизвестный отзывается молча. */
    public void revoke(TurnToken token) {
        issued.remove(token.value());
    }

    private record Issued(UserId user, Instant expiresAt) {
    }
}
