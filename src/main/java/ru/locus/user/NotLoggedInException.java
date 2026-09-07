package ru.locus.user;

/**
 * Текущего пользователя спросили там, где входа нет.
 *
 * Это ошибка программиста, а не посетителя: неаутентифицированный запрос
 * до сервисного слоя не доходит — его останавливает цепочка фильтров.
 */
public class NotLoggedInException extends IllegalStateException {

    public NotLoggedInException(String message) {
        super(message);
    }
}
