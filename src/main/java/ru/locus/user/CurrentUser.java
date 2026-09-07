package ru.locus.user;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Единственный компонент, знающий о вошедшем.
 *
 * Так требует ADR-0027: владельца личных данных подставляет сервисный слой,
 * беря его отсюда, а не читая из запроса. Идентификатор, пришедший снаружи, —
 * это чужие данные по первому же запросу; поэтому знание о входе собрано
 * в одном месте, и в остальном коде его быть не должно.
 */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /** Идентификатор вошедшего — тот самый параметр владельца из ADR-0027. */
    public UserId id() {
        return account().id();
    }

    public String login() {
        return account().login();
    }

    /** Учётная запись вошедшего вместе с ролями. */
    public User account() {
        return loggedIn().orElseThrow(() -> new NotLoggedInException(
                "Обращение к текущему пользователю без выполненного входа"));
    }

    /**
     * Учётная запись вошедшего, если вход выполнен.
     *
     * Нужно там, где отсутствие входа — обычное дело, а не ошибка: цепочка
     * фильтров работает и на запросах анонимного посетителя.
     */
    public Optional<User> loggedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String login = authentication.getName();
        return Optional.of(users.findByLogin(login).orElseThrow(() -> new NotLoggedInException(
                "Вошедший под именем «" + login + "» не найден: учётная запись исчезла во время сеанса")));
    }
}
