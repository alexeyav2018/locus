package ru.locus.assistant;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Идущие Реплики и их процессы — для «Остановить» и для остановки
 * при выключении Локуса (design.md agent-runner, Р3, Р9).
 *
 * <p>Запрет второй Реплики учителя держит не эта карта, а база — частичный
 * уникальный индекс: запрет в памяти не пережил бы двух запросов в разных
 * потоках без собственной блокировки. Здесь только то, чего в базе нет, —
 * сам процесс.
 *
 * <p>Реплика попадает сюда до запуска процесса: «Остановить», пришедшее,
 * пока рабочее место ещё разворачивается, процесс убьёт сразу при запуске.
 */
@Component
public class RunningTurns {

    private final ConcurrentMap<AssistantTurnId, Running> running = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    /** Отметить Реплику идущей; процесс к ней прикрепит исполнитель. */
    Running register(AssistantTurnId turn) {
        Running handle = new Running();
        running.put(turn, handle);
        return handle;
    }

    /** Реплика завершилась — исход записан. */
    void remove(AssistantTurnId turn) {
        running.remove(turn);
    }

    /**
     * Остановить идущую Реплику: процесс убивается, исход «остановлено»
     * записывает исполнитель. Владельца проверяет сервис до вызова.
     *
     * @return была ли Реплика здесь
     */
    boolean stop(AssistantTurnId turn) {
        Optional<Running> handle = Optional.ofNullable(running.get(turn));
        handle.ifPresent(Running::stop);
        return handle.isPresent();
    }

    /** Локус выключается: исход не записывается, строку при старте пометит уборка. */
    boolean shuttingDown() {
        return shuttingDown;
    }

    /**
     * Выключение Локуса убивает идущие процессы. Строки остаются «работаю»
     * и при следующем старте становятся «прервано перезапуском» — записывать
     * исход в выключающуюся базу незачем.
     */
    @PreDestroy
    void stopAll() {
        shuttingDown = true;
        running.values().forEach(Running::kill);
    }

    /** Процесс одной Реплики и отметка «остановлено учителем». */
    static final class Running {

        private volatile boolean stopped;
        private volatile Process process;

        /** Прикрепить запущенный процесс; если Реплику уже остановили — убить сразу. */
        void attach(Process started) {
            process = started;
            if (stopped) {
                started.destroy();
            }
        }

        boolean stopped() {
            return stopped;
        }

        private void stop() {
            stopped = true;
            kill();
        }

        private void kill() {
            Process current = process;
            if (current != null) {
                current.destroy();
            }
        }
    }
}
