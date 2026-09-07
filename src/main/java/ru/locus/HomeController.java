package ru.locus;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;
import ru.locus.user.User;

/**
 * Главная страница вошедшего.
 *
 * Прикладного содержания в ней пока нет: разделы появятся вместе с
 * рубрикатором, задачами и учениками. Сейчас она показывает, кто вошёл,
 * и ведёт к тому немногому, что уже есть.
 *
 * Ссылки показываются по ролям, но правами это не является: настоящая
 * проверка стоит на методах сервисов, и обращение по прямому адресу
 * отклоняется независимо от разметки.
 */
@Controller
public class HomeController {

    private final CurrentUser currentUser;

    public HomeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping(Addresses.HOME)
    public String show(Model model) {
        User user = currentUser.account();
        model.addAttribute("login", user.login());
        model.addAttribute("administrator", user.hasRole(Role.ADMINISTRATOR));
        return "home";
    }
}
