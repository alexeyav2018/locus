package ru.locus.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Реестр инструментов — единственный список того, что умеет Помощник
 * (ADR-0056). По нему строится сервер MCP, и с ним сверяются навыки
 * Помощника тестом (ADR-0057): инструмент, которого здесь нет, модели
 * не предлагается, а навык, называющий выдуманный инструмент, роняет
 * сборку.
 *
 * <p>Собирается из реализаций {@link Tools} списком, в порядке областей.
 * Повтор имени — ошибка при старте, а не тихая подмена: две области,
 * назвавшие инструмент одинаково, дали бы модели один из двух наугад.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tools> providers) {
        Map<String, Tool> collected = new LinkedHashMap<>();
        for (Tools provider : providers) {
            for (Tool tool : provider.tools()) {
                Tool clash = collected.put(tool.name(), tool);
                if (clash != null) {
                    throw new IllegalStateException("Инструмент «" + tool.name()
                            + "» объявлен дважды: " + provider.getClass().getSimpleName()
                            + " и ещё где-то; имя в реестре неповторимо");
                }
            }
        }
        this.byName = Collections.unmodifiableMap(collected);
    }

    /** Все инструменты в порядке объявления. */
    public List<Tool> tools() {
        return List.copyOf(byName.values());
    }

    /** Имена инструментов в порядке объявления — то, с чем сверяются навыки. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
