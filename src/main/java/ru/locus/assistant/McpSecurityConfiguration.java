package ru.locus.assistant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import ru.locus.Addresses;
import ru.locus.user.UserRepository;

/**
 * Вторая цепочка безопасности — для сервера MCP (design.md, Р3).
 *
 * <p>Основная цепочка ({@code SecurityConfig}) устроена для браузера:
 * форма входа, серверная сессия, CSRF, фильтр смены пароля. Помощнику
 * ничего из этого не подходит: сеанса у его процесса нет, и ответом
 * на отсутствие входа должна быть не форма, а {@code 401}. Поэтому
 * у {@link Addresses#MCP} своя цепочка, раньше основной, и в ней:
 * <ul>
 * <li>пропуск — только токен Реплики ({@link TurnTokenFilter}), любой
 * другой запрос получает {@code 401};</li>
 * <li>без сессии ({@code STATELESS}): между вызовами сервер ничего
 * не держит (ADR-0054), личность приносит каждый запрос сам;</li>
 * <li>без CSRF: запросы не из браузера, пропуском служит токен,
 * которого у браузера нет;</li>
 * <li>без фильтра смены пароля: записи с паролем к смене не доступна
 * ни одна страница, значит, и Реплику ей не начать.</li>
 * </ul>
 * Основная цепочка не меняется: {@code /mcp} ей не достаётся.
 */
@Configuration
public class McpSecurityConfiguration {

    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, TurnTokens tokens,
                                                      UserRepository users, UserDetailsService accounts)
            throws Exception {
        http
                .securityMatcher(Addresses.MCP)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                // Если до проверки прав всё же дошёл запрос без вошедшего —
                // тот же отказ, что у фильтра, а не форма входа.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, refused) -> TurnTokenFilter.refuse(response)))
                // Раньше проверки прав: она и должна увидеть вошедшего по токену.
                .addFilterBefore(new TurnTokenFilter(tokens, users, accounts), AuthorizationFilter.class);
        return http.build();
    }
}
