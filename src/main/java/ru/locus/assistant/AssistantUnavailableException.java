package ru.locus.assistant;

/**
 * Отказ начать Реплику, пока Помощник недоступен: нет команды, чужая версия
 * или нет ключа (design.md agent-runner, Р10). Причина — та же, что называет
 * страница Помощника.
 */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String cause) {
        super("Помощник недоступен: " + cause);
    }
}
