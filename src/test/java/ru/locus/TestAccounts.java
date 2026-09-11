package ru.locus;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.user.UserRepository;

/**
 * Учётные записи для тестов.
 *
 * Заводятся через репозиторий, а не через сервис: сервис требует прав
 * Администратора, а тесту нужно подготовить обстановку, а не проверить её.
 * Пароль кодируется тем же bean'ом, что и в работе, — иначе вход не пройдёт.
 *
 * Тесты идут на одной базе, поэтому имя входа у каждой записи своё.
 */
@Component
public class TestAccounts {

    /** Пароль, которым тесты входят, если не сказано иного. */
    public static final String PASSWORD = "пароль-для-теста";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public TestAccounts(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Запись с уже сменённым паролем: работает по правам своих ролей. */
    public Account settled(Role... roles) {
        return create(PASSWORD, false, roles);
    }

    /** Запись с назначенным паролем: до смены ей не доступно ничего. */
    public Account withPasswordToChange(Role... roles) {
        return create(PASSWORD, true, roles);
    }

    public Account create(String password, boolean passwordChangeRequired, Role... roles) {
        String login = "test-" + UUID.randomUUID();
        UserId id = users.create(login, passwordEncoder.encode(password), passwordChangeRequired);
        for (Role role : roles) {
            users.assignRole(id, role);
        }
        return new Account(id, login, password, Set.copyOf(Arrays.asList(roles)));
    }

    /**
     * Заведённая запись: то, что нужно знать тесту, чтобы войти от её имени —
     * формой (имя входа и пароль) или напрямую в контекст безопасности
     * ({@link LoggedIn#as(Account)}, для которого и хранятся роли).
     */
    public record Account(UserId id, String login, String password, Set<Role> roles) {
    }
}
