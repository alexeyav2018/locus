package ru.locus;

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
 * способом: роль в контексте, без учётной записи и без входа формой.
 * Сами права проверяются отдельно и по-настоящему, через HTTP.
 */
public final class LoggedIn {

    private LoggedIn() {
    }

    public static void as(Role... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("тест", null, authorities));
    }

    public static void nobody() {
        SecurityContextHolder.clearContext();
    }
}
