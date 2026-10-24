package ru.locus.assistant;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Помощник при старте Локуса (design.md agent-runner, Р3, Р10): сначала
 * уборка — Реплики, оставшиеся в «работаю», становятся «прервано
 * перезапуском», их процессов больше нет; затем проверка доступности.
 * Образец — уборка черновиков сборки ({@code AssemblyDraftConfiguration}).
 *
 * <p>Старт не прерывается ни при каком итоге: без Помощника Локус работает.
 */
@Component
public class AgentStartup implements ApplicationRunner {

    /** Причина у Реплики, оборванной перезапуском. */
    static final String INTERRUPTED = "Локус перезапущен, пока Помощник отвечал";

    private static final Logger log = LoggerFactory.getLogger(AgentStartup.class);

    private final AssistantRepository repository;
    private final AssistantService service;
    private final AgentProperties properties;
    private final AgentEnvironment environment;
    private final Clock clock;

    public AgentStartup(AssistantRepository repository, AssistantService service, AgentProperties properties,
            AgentEnvironment environment, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int interrupted = repository.interruptRunning(INTERRUPTED, clock.instant());
        if (interrupted > 0) {
            log.info("Реплик Помощника, прерванных перезапуском: {}", interrupted);
        }
        AssistantAvailability availability = AssistantAvailability.check(properties.executable(),
                properties.version(), environment);
        service.availability(availability);
        if (availability.available()) {
            log.info("Помощник доступен: Claude Code {}", availability.version());
        } else {
            log.warn("Помощник недоступен: {}", availability.cause());
        }
    }
}
