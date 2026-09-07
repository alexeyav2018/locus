package ru.locus.startovaya;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Стартовая страница. Содержания в ней нет: она подтверждает, что серверная
 * сборка разметки работает. Уйдёт, когда появится настоящий вход (auth-roles).
 */
@Controller
public class StartovayaStranicaController {

    private final String imyaPrilozheniya;

    public StartovayaStranicaController(@Value("${spring.application.name}") String imyaPrilozheniya) {
        this.imyaPrilozheniya = imyaPrilozheniya;
    }

    @GetMapping("/")
    public String pokazat(Model model) {
        model.addAttribute("prilozhenie", imyaPrilozheniya);
        return "startovaya";
    }
}
