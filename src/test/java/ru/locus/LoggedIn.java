package ru.locus;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.locus.user.Role;

/**
 * Вошедший — для тестов, которые зовут сервис напрямую.
 *
 * Права проверяются на методах сервиса, поэтому вызов без контекста
 * безопасности отклоняется — и правильно делает. Тесту, проверяющему правила
 * области, а не права, нужен вошедший, и здесь он ставится минимальным
 * способом: роль в контексте, без входа формой.
 * Сами права проверяются отдельно и по-настоящему, через HTTP.
 *
 * Два способа различаются тем, есть ли за вошедшим учётная запись.
 * {@link #as(Role...)} обходится без неё — этого хватает библиотеке, где
 * владельца нет. Личному контуру нужен настоящий {@link ru.locus.user.UserId}:
 * {@link ru.locus.user.CurrentUser#id()} ищет запись по имени входа, и на
 * вошедшем без записи падает. Для него — {@link #as(TestAccounts.Account)}.
 */
public final class LoggedIn {

    private LoggedIn() {
    }

    /** Вошедший без учётной записи: только роли. Владельца у него нет. */
    public static void as(Role... roles) {
        authenticate("тест", Arrays.asList(roles));
    }

    /**
     * Вошедший от имени заведённой записи: имя входа и роли — её,
     * и {@link ru.locus.user.CurrentUser#id()} отдаёт её идентификатор.
     */
    public static void as(TestAccounts.Account account) {
        authenticate(account.login(), account.roles());
    }

    public static void nobody() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String login, Collection<Role> roles) {
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(login, null, authorities));
    }
}
