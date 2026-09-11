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
    public static final String TAXONOMY = "/taxonomy";
    public static final String DICTIONARIES = "/dictionaries";
    public static final String PROBLEMS = "/problems";
    public static final String THEORY = "/theory";

    /**
     * Личный контур Учителя. Разграничение здесь не по адресу, а по роли
     * на сервисах ({@code hasRole('TEACHER')}) и по владельцу в репозитории
     * (ADR-0027): адрес одинаков для всех Учителей, а показывают они каждому
     * своё.
     */
    public static final String STUDENTS = "/students";
    public static final String GROUPS = "/groups";

    /**
     * Отдача файла по подписанной ссылке. Адрес открыт без входа: пропуск даёт
     * подпись, а не сеанс — ровно как у объектного хранилища, куда запрос идёт
     * мимо приложения.
     */
    public static final String FILE = "/file";

    private Addresses() {
    }
}
