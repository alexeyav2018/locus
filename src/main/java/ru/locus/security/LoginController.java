package ru.locus.security;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;

/**
 * Форма входа — единственная страница, доступная без входа.
 *
 * Отправку формы обрабатывает сам Spring Security по тому же адресу; здесь
 * только показ. Контроллер тонкий: ни предметной логики, ни проверок прав
 * (standards.md, «Слои и границы»).
 */
@Controller
public class LoginController {

    /**
     * Сообщение об отказе одно на все случаи: по нему нельзя отличить неверный
     * пароль от несуществующего имени входа.
     */
    private static final String REFUSED = "Неверное имя входа или пароль";

    @GetMapping(Addresses.LOGIN)
    public String show(@RequestParam(required = false) String error,
                       @RequestParam(required = false) String logout,
                       Model model) {
        if (error != null) {
            model.addAttribute("error", REFUSED);
        }
        if (logout != null) {
            model.addAttribute("notice", "Сеанс завершён");
        }
        return "login";
    }
}
