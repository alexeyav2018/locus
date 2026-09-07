package ru.locus.user;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Пользователь — учётная запись входа.
 *
 * Набор ролей произвольный: Администратор, Учитель, обе сразу или ни одной.
 * Пользователь без ролей входит, но ни одной операции не получает.
 *
 * @param passwordChangeRequired пароль назначен не владельцем записи (заведён
 *                               вместе с ней или сброшен Администратором)
 *                               и подлежит смене при первом входе (ADR-0026)
 */
public record User(UserId id, String login, String passwordHash, boolean passwordChangeRequired, Set<Role> roles) {

    public User {
        roles = roles.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Role.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
