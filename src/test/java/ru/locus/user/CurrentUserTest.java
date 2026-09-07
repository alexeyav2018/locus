package ru.locus.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.locus.IntegrationTest;

/**
 * {@link CurrentUser} — единственный компонент, знающий о вошедшем (ADR-0027).
 *
 * Проверяется, что он отдаёт идентификатор владельца, который сервисный слой
 * подставит в запросы к личным данным, и что без входа он не отдаёт ничего.
 */
class CurrentUserTest extends IntegrationTest {

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private UserRepository users;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsIdentifierOfTheLoggedInUser() {
        String login = "current-" + UUID.randomUUID();
        UserId id = users.create(login, "{noop}пароль", false);
        users.assignRole(id, Role.TEACHER);
        logInAs(login);

        assertThat(currentUser.id()).isEqualTo(id);
        assertThat(currentUser.login()).isEqualTo(login);
        assertThat(currentUser.account().roles()).containsExactly(Role.TEACHER);
    }

    @Test
    void withoutLoginFailsWithATellingMessage() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(currentUser::id)
                .isInstanceOf(NotLoggedInException.class)
                .hasMessageContaining("без выполненного входа");
    }

    @Test
    void anonymousVisitorIsNotALoggedInUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(currentUser::id)
                .as("аноним — не вошедший, сколько бы он ни выглядел аутентифицированным")
                .isInstanceOf(NotLoggedInException.class);
    }

    private static void logInAs(String login) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
    }
}
