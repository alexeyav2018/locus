package ru.locus;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;
import ru.locus.user.User;

/**
 * Данные для общей шапки страниц: кто вошёл, какие разделы ему показать
 * и какой из них сейчас открыт.
 *
 * Шапка одна на все экраны ({@code templates/fragments/shell.html}), поэтому
 * данные для неё берутся здесь, а не повторяются в каждом контроллере. Имя
 * атрибута {@code shell} не пересекается с тем, что кладут контроллеры, —
 * экраны вроде главной по-прежнему сами решают, что показать в теле.
 *
 * Как и ссылки на главной, пункты шапки — удобство, а не права: настоящая
 * проверка стоит на методах сервисов, и прямой адрес отклоняется независимо
 * от того, нарисована ли ссылка на него.
 */
@ControllerAdvice
public class ShellAdvice {

    private final CurrentUser currentUser;

    public ShellAdvice(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * Что показывает шапка.
     *
     * @param login         имя вошедшего
     * @param administrator показывать ли раздел учётных записей
     * @param teacher       показывать ли личный контур
     * @param section       открытый раздел — по нему подсвечивается пункт
     */
    public record Shell(String login, boolean administrator, boolean teacher, String section) {
    }

    /** {@code null} для посетителя без входа: у формы входа шапки нет. */
    @ModelAttribute("shell")
    public Shell shell(HttpServletRequest request) {
        Optional<User> user = currentUser.loggedIn();
        return user.map(account -> new Shell(
                        account.login(),
                        account.hasRole(Role.ADMINISTRATOR),
                        account.hasRole(Role.TEACHER),
                        sectionOf(request)))
                .orElse(null);
    }

    private static String sectionOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals(Addresses.HOME)) {
            return "home";
        }
        if (path.startsWith(Addresses.TAXONOMY) || path.startsWith(Addresses.THEORY)) {
            return "taxonomy";
        }
        if (path.startsWith(Addresses.PROBLEMS)) {
            return "problems";
        }
        if (path.startsWith(Addresses.DICTIONARIES)) {
            return "dictionaries";
        }
        if (path.startsWith(Addresses.STUDENTS) || path.startsWith(Addresses.MASTERY)) {
            return "students";
        }
        if (path.startsWith(Addresses.WORKS)) {
            // Работы одного Ученика — часть его карточки, Работы Задания — часть Заданий.
            return request.getParameter("student") != null ? "students" : "assignments";
        }
        if (path.startsWith(Addresses.GROUPS)) {
            return "groups";
        }
        if (path.startsWith(Addresses.ASSIGNMENTS)) {
            return "assignments";
        }
        if (path.startsWith(Addresses.USERS)) {
            return "users";
        }
        return "";
    }
}
