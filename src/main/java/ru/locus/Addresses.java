package ru.locus;

/**
 * Адреса, о которых должны договориться настройка безопасности, фильтр смены
 * пароля и контроллеры. Разъехавшиеся строки здесь — тихая дыра: адрес,
 * который фильтр считает открытым, а контроллер обслуживает под другим именем.
 */
public final class Addresses {

    public static final String HOME = "/";
    public static final String LOGIN = "/login";
    public static final String LOGOUT = "/logout";
    public static final String PASSWORD_CHANGE = "/password";
    public static final String USERS = "/users";

    private Addresses() {
    }
}
