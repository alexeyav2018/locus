package ru.locus.assistant;

/**
 * Результат инструмента, как его отдаёт обработчик серверу (design.md, Р5).
 *
 * <p>Три вида, и только три: данные — объект, который сервер превратит
 * в JSON; картинка — страница или кусок страницы в JPEG; отказ — текст
 * того же отказа, который увидел бы учитель на странице. Запечатан:
 * сервер разбирает виды перебором, и четвёртый вид, заведённый молча,
 * не скомпилирует перебор, а не проскочит мимо него.
 *
 * <p>Отказ — результат, а не исключение: модель читает его и действует
 * дальше, как учитель читает красную строку на странице; соединение
 * при этом не рвётся.
 */
public sealed interface ToolResult {

    /** Данные: запись с плоскими полями, которую сервер отдаёт JSON. */
    static ToolResult json(Object value) {
        return new Json(value);
    }

    /** Картинка JPEG — страница или кусок страницы. */
    static ToolResult image(byte[] jpeg) {
        return new Image(jpeg);
    }

    /** Отказ с текстом для человека. */
    static ToolResult refusal(String message) {
        return new Refusal(message);
    }

    /**
     * Данные для модели. Наружу идут записи с полями {@code long},
     * {@code String} и списками: идентификаторы-обёртки проекта остаются
     * внутри, модели нужны числа и имена.
     */
    record Json(Object value) implements ToolResult {
        public Json {
            if (value == null) {
                throw new IllegalArgumentException("Результат инструмента не может быть пустым");
            }
        }
    }

    /** Картинка JPEG. */
    record Image(byte[] jpeg) implements ToolResult {
        public Image {
            if (jpeg == null || jpeg.length == 0) {
                throw new IllegalArgumentException("Картинка результата пуста");
            }
        }
    }

    /** Отказ сервиса или разбора аргументов — текстом для человека. */
    record Refusal(String message) implements ToolResult {
        public Refusal {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("У отказа нет текста");
            }
        }
    }
}
