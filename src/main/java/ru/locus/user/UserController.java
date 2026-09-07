package ru.locus.user;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;

/**
 * Страницы учётных записей: смена собственного пароля и — для Администратора —
 * ведение чужих записей.
 *
 * Контроллер тонкий: ни предметной логики, ни проверок прав. Права проверяет
 * {@link UserService} на каждой операции, поэтому отказ происходит одинаково
 * и при переходе по ссылке, и при обращении по прямому адресу
 * (standards.md, «Слои и границы»).
 */
@Controller
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping(Addresses.PASSWORD_CHANGE)
    public String passwordForm() {
        return "user/password";
    }

    @PostMapping(Addresses.PASSWORD_CHANGE)
    public String changePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 Model model) {
        try {
            users.changeOwnPassword(currentPassword, newPassword);
        } catch (WrongPasswordException | IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return "user/password";
        }
        return "redirect:" + Addresses.HOME;
    }

    @GetMapping(Addresses.USERS)
    public String list(Model model) {
        model.addAttribute("users", users.all());
        model.addAttribute("roles", Role.values());
        return "user/list";
    }

    @PostMapping(Addresses.USERS)
    public String create(@RequestParam String login, @RequestParam String initialPassword, Model model) {
        try {
            users.create(login, initialPassword);
        } catch (LoginAlreadyTakenException | IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return list(model);
        }
        return "redirect:" + Addresses.USERS;
    }

    @PostMapping(Addresses.USERS + "/{id}/roles")
    public String changeRole(@PathVariable long id, @RequestParam Role role, @RequestParam boolean assign) {
        UserId user = new UserId(id);
        if (assign) {
            users.assignRole(user, role);
        } else {
            users.revokeRole(user, role);
        }
        return "redirect:" + Addresses.USERS;
    }

    @PostMapping(Addresses.USERS + "/{id}/password")
    public String resetPassword(@PathVariable long id, @RequestParam String newPassword) {
        users.resetPassword(new UserId(id), newPassword);
        return "redirect:" + Addresses.USERS;
    }
}
