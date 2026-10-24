package ru.locus.assistant;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.locus.Addresses;

/**
 * Страница Помощника — пока только его доступность (design.md agent-runner,
 * Р11): «доступен» с закреплённой версией либо «недоступен» с причиной,
 * найденной при старте ({@link AgentStartup}). Видна любому вошедшему —
 * и Учителю, и Администратору. Пункта в шапке нет: без разговора на странице
 * нечего делать, пункт добавит {@code assistant-chat}.
 *
 * <p>Контроллер тонкий (standards.md, «Слои и границы»): итог проверки
 * берётся у {@link AssistantService} как есть.
 */
@Controller
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @GetMapping(Addresses.ASSISTANT)
    public String page(Model model) {
        model.addAttribute("availability", service.availability());
        return "assistant/page";
    }
}
