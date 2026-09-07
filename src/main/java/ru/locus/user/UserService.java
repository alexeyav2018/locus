package ru.locus.user;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Операции над учётными записями.
 *
 * Проверки прав стоят здесь, а не в контроллерах и не на адресах
 * (standards.md, «Слои и границы»): правило привязано к операции и
 * срабатывает при любом способе вызова, включая новый контроллер,
 * о котором сейчас никто не думает.
 *
 * Учётные записи — общее, под Администратором: фильтра по владельцу здесь
 * нет и быть не должно (ADR-0027).
 */
@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, CurrentUser currentUser) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public List<User> all() {
        return users.findAll();
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public User byId(UserId id) {
        return existing(id);
    }

    /**
     * Заводит учётную запись с начальным паролем. Пароль назначен не владельцем
     * записи, поэтому сразу помечается подлежащим смене (ADR-0026).
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public UserId create(String login, String initialPassword) {
        String trimmed = login == null ? "" : login.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Имя входа не может быть пустым");
        }
        if (users.findByLogin(trimmed).isPresent()) {
            throw new LoginAlreadyTakenException(trimmed);
        }
        return users.create(trimmed, passwordEncoder.encode(initialPassword), true);
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public void assignRole(UserId id, Role role) {
        existing(id);
        users.assignRole(id, role);
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public void revokeRole(UserId id, Role role) {
        existing(id);
        users.revokeRole(id, role);
    }

    /** Сброшенный Администратором пароль подлежит смене при первом же входе. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public void resetPassword(UserId id, String newPassword) {
        existing(id);
        users.updatePassword(id, passwordEncoder.encode(newPassword), true);
    }

    /**
     * Смена собственного пароля. Доступна любому вошедшему — в том числе тому,
     * кому до неё не доступно больше ничего.
     */
    @Transactional
    public void changeOwnPassword(String currentPassword, String newPassword) {
        User user = currentUser.account();
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new WrongPasswordException();
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Новый пароль не может быть пустым");
        }
        users.updatePassword(user.id(), passwordEncoder.encode(newPassword), false);
    }

    private User existing(UserId id) {
        return users.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "Учётной записи с идентификатором " + id.value() + " не существует"));
    }
}
