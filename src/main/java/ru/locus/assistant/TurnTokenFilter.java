package ru.locus.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.locus.user.User;
import ru.locus.user.UserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Замок сервера MCP: вход по токену Реплики (ADR-0057; design.md, Р3).
 *
 * <p>Читает {@code Authorization: Bearer}, узнаёт по {@link TurnTokens}
 * Пользователя и ставит в контекст безопасности ту же {@link Authentication},
 * что ставит вход формой: имя входа и роли с префиксом {@code ROLE_} —
 * через тот же {@link UserDetailsService}, что и форма, чтобы префикс
 * по-прежнему добавлялся в одном месте. Дальше сервисный слой работает
 * как для страницы: {@code CurrentUser} ищет вошедшего по имени,
 * {@code @PreAuthorize} проверяет роли, и ни один сервис не знает,
 * что вошедшего поставил не браузер.
 *
 * <p>Нет токена, или он неизвестен, отозван, истёк — {@code 401}
 * с {@code WWW-Authenticate: Bearer}, без перенаправления на форму входа:
 * у процесса Помощника браузера нет, и форма ему ни к чему. Отказ пишется
 * в ответ прямо, минуя страницу ошибки: та — для человека.
 *
 * <p>Стоит только в цепочке {@link McpSecurityConfiguration}, поэтому
 * бином не объявлен: бин-фильтр Spring Boot повесил бы на все адреса.
 */
public class TurnTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final TurnTokens tokens;
    private final UserRepository users;
    private final UserDetailsService accounts;

    public TurnTokenFilter(TurnTokens tokens, UserRepository users, UserDetailsService accounts) {
        this.tokens = tokens;
        this.users = users;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Authentication> bearer = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearer.isEmpty()) {
            refuse(response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(bearer.get());
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<Authentication> authenticate(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String value = header.substring(BEARER.length()).trim();
        try {
            return tokens.resolve(value)
                    .flatMap(users::findById)
                    .map(User::login)
                    .map(accounts::loadUserByUsername)
                    .map(TurnTokenFilter::authentication);
        } catch (UsernameNotFoundException gone) {
            // Запись исчезла между выпуском токена и вызовом: токен мёртв.
            return Optional.empty();
        }
    }

    private static Authentication authentication(UserDetails details) {
        return UsernamePasswordAuthenticationToken.authenticated(
                details.getUsername(), null, details.getAuthorities());
    }

    /** Отказ без живого токена — один для отсутствующего, неизвестного, отозванного и истёкшего. */
    static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("Нужен живой токен Реплики");
        response.flushBuffer();
    }
}
