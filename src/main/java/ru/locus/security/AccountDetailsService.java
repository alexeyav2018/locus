package ru.locus.security;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.locus.user.Role;
import ru.locus.user.User;
import ru.locus.user.UserRepository;

/**
 * Мост между учётной записью системы и представлением пользователя
 * в Spring Security.
 *
 * Готовый {@code JdbcUserDetailsManager} не взят: он приносит собственную
 * схему (таблицы {@code users} и {@code authorities}), а имена таблиц в этом
 * проекте выводятся из словаря, а не выбираются (glossary.md, «Имена в коде
 * и в базе»).
 *
 * Префикс {@code ROLE_} добавляется здесь и только здесь: {@code hasRole(...)}
 * его требует, но к словарю он отношения не имеет и в базу не попадает.
 */
@Service
public class AccountDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AccountDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        User user = users.findByLogin(login)
                .orElseThrow(() -> new UsernameNotFoundException("Нет учётной записи с именем входа " + login));
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.login())
                .password(user.passwordHash())
                .authorities(authorities(user))
                .build();
    }

    private static List<SimpleGrantedAuthority> authorities(User user) {
        return user.roles().stream()
                .map(Role::name)
                .map(name -> new SimpleGrantedAuthority("ROLE_" + name))
                .toList();
    }
}
