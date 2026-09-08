package ru.locus.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.locus.Addresses;
import ru.locus.user.CurrentUser;
import ru.locus.user.User;

/**
 * Пароль, назначенный не владельцем записи, подлежит смене при первом входе
 * (ADR-0026). До смены вошедшему доступны только форма смены пароля, её
 * отправка, выход и статика; всё прочее ведёт на форму смены.
 *
 * Фильтр, а не перехватчик Spring MVC: спецификация требует, чтобы эта
 * проверка срабатывала **раньше** проверки прав, иначе ограничение обходится
 * обращением по прямому адресу. Перехватчик работает после фильтров
 * безопасности, то есть уже после авторизации.
 *
 * Признак читается из базы на каждом запросе, а не запоминается при входе:
 * запомненный пришлось бы обновлять после смены пароля, и забытое обновление
 * оставило бы человека запертым на форме смены до перезахода.
 */
public class PasswordChangeFilter extends OncePerRequestFilter {

    private final CurrentUser currentUser;
    private final RequestMatcher staticResources;

    public PasswordChangeFilter(CurrentUser currentUser, RequestMatcher staticResources) {
        this.currentUser = currentUser;
        this.staticResources = staticResources;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allowedRegardlessOfTheFlag(request) || !passwordMustBeChanged()) {
            chain.doFilter(request, response);
            return;
        }
        response.sendRedirect(request.getContextPath() + Addresses.PASSWORD_CHANGE);
    }

    private boolean allowedRegardlessOfTheFlag(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return Addresses.PASSWORD_CHANGE.equals(path)
                || Addresses.LOGIN.equals(path)
                || Addresses.LOGOUT.equals(path)
                // Подписанная ссылка не про сеанс: она открывается и тем, кому
                // предстоит сменить пароль, — иначе поведение зависело бы
                // от того, какой вариант хранилища включён.
                || path.startsWith(Addresses.FILE + "/")
                || staticResources.matches(request);
    }

    private boolean passwordMustBeChanged() {
        return currentUser.loggedIn().map(User::passwordChangeRequired).orElse(false);
    }
}
