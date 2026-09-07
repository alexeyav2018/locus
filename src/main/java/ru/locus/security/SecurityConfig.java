package ru.locus.security;

import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ru.locus.Addresses;
import ru.locus.user.CurrentUser;

/**
 * Настройка входа: форма, серверная сессия, закрытые адреса (ADR-0026).
 *
 * Здесь стоит только грубый рубеж — «всё требует входа». Разграничение по
 * ролям на уровне адресов сознательно не дублируется: оно живёт на методах
 * сервисов (standards.md, «Слои и границы»), а два описания одного правила
 * расходятся молча.
 *
 * CSRF-защита включена (умолчание Spring Security): формы Thymeleaf
 * подставляют токен сами, и отключать её на серверных формах незачем.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Делегирующий кодировщик: хеш хранится в виде {@code {алгоритм}хеш},
     * поэтому алгоритм можно сменить позже, не трогая уже сохранённые пароли —
     * старые проверяются прежним, новые пишутся новым.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CurrentUser currentUser) throws Exception {
        RequestMatcher staticResources = PathRequest.toStaticResources().atCommonLocations();
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(staticResources).permitAll()
                        .requestMatchers(Addresses.LOGIN).permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage(Addresses.LOGIN)
                        .loginProcessingUrl(Addresses.LOGIN)
                        .defaultSuccessUrl(Addresses.HOME, true)
                        .failureUrl(Addresses.LOGIN + "?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl(Addresses.LOGOUT)
                        .logoutSuccessUrl(Addresses.LOGIN + "?logout"))
                // Раньше проверки прав: иначе ограничение обходится прямым адресом.
                .addFilterBefore(new PasswordChangeFilter(currentUser, staticResources), AuthorizationFilter.class);
        return http.build();
    }
}
