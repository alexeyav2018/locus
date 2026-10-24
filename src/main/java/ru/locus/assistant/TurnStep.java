package ru.locus.assistant;

/**
 * Строка хода — вызов инструмента Помощником в Реплике, в порядке вызова.
 * Пишется сразу, как процесс сообщил о вызове: ход виден, пока Реплика идёт.
 *
 * @param position  номер вызова в Реплике, с единицы
 * @param toolName  имя инструмента, как его назвал процесс ({@code mcp__locus__…})
 * @param arguments аргументы вызова JSON-текстом, обрезанные (design.md, Р8)
 */
public record TurnStep(int position, String toolName, String arguments) {

    /** Приставка инструментов сервера Локуса в имени, которое видит модель. */
    private static final String LOCUS_PREFIX = "mcp__locus__";

    /**
     * Имя инструмента для показа учителю: инструмент Локуса — без приставки
     * {@code mcp__locus__}, чужой — как назван.
     */
    public String tool() {
        return toolName.startsWith(LOCUS_PREFIX) ? toolName.substring(LOCUS_PREFIX.length()) : toolName;
    }
}
