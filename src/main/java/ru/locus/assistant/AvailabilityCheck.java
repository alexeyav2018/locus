package ru.locus.assistant;

import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Итог проверки доступности Помощника и её повтор (спека {@code assistant-turns},
 * «Помощник без команды недоступен с причиной»; design.md
 * assistant-availability-recheck, Р1–Р4).
 *
 * <p>Проверяет только обращение: старт Локуса, открытие страницы, запрос
 * Реплики. Своего потока и таймера нет (ADR-0014, ADR-0054). Доступный
 * Помощник не проверяется — команда не запускается; недоступный проверяется
 * при каждом обращении, и обращение ждёт итога. Две проверки разом не идут:
 * заставшее идущую проверку обращение сразу получает прежний итог.
 */
@Component
public class AvailabilityCheck {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCheck.class);

    private final AgentProperties properties;
    private final AgentEnvironment environment;
    private final ReentrantLock checking = new ReentrantLock();

    private volatile AssistantAvailability last = AssistantAvailability.notChecked();

    public AvailabilityCheck(AgentProperties properties, AgentEnvironment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /** Прежний итог, без проверки. */
    public AssistantAvailability last() {
        return last;
    }

    /** Итог с повторной проверкой, если Помощник недоступен и проверка не идёт. */
    public AssistantAvailability current() {
        if (last.available() || !checking.tryLock()) {
            return last;
        }
        try {
            if (last.available()) {
                return last;
            }
            AssistantAvailability checked = AssistantAvailability.check(properties.executable(),
                    properties.version(), environment);
            report(checked);
            last = checked;
            return checked;
        } finally {
            checking.unlock();
        }
    }

    /** В журнал — только смена итога: иначе каждое открытие страницы при сбое повторяло бы строку. */
    private void report(AssistantAvailability checked) {
        if (checked.available()) {
            log.info("Помощник доступен: Claude Code {}", checked.version());
        } else if (!checked.cause().equals(last.cause())) {
            log.warn("Помощник недоступен: {}", checked.cause());
        }
    }
}
