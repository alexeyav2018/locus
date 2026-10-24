package ru.locus.assistant;

/**
 * Состояние Реплики (design.md agent-runner, Р2).
 *
 * <p>Идёт только {@link #RUNNING}; остальные шесть — конечные, и из конечного
 * Реплика не выходит. «Прервано по шагам» отдельно от «не удалось»
 * намеренно: исчерпанный предел ходов — не сбой, а предел, симметричный
 * пределу времени, и учителю есть что сделать — сказать «продолжай» в том же
 * Разговоре.
 *
 * <p>Слово состояния даёт перечисление, а не шаблон: шаблоны только
 * отображают (standards.md).
 */
public enum TurnState {

    RUNNING("работаю"),
    DONE("готово"),
    FAILED("не удалось"),
    STOPPED("остановлено"),
    TIMED_OUT("прервано по времени"),
    STEP_LIMIT("прервано по шагам"),
    INTERRUPTED("прервано перезапуском");

    private final String word;

    TurnState(String word) {
        this.word = word;
    }

    /** Состояние словом, как его видит учитель. */
    public String word() {
        return word;
    }

    /** Реплика ещё идёт. */
    public boolean running() {
        return this == RUNNING;
    }

    /**
     * Тон значка состояния — окончание класса {@code badge--…} в
     * {@code locus.css} (design.md assistant-chat, Р8): идёт — сведения,
     * готово — удача, остановлено учителем и предел шагов — предупреждение
     * (учителю есть что сделать), прочее — неудача.
     */
    public String tone() {
        return switch (this) {
            case RUNNING -> "info";
            case DONE -> "ok";
            case STOPPED, STEP_LIMIT -> "warn";
            case FAILED, TIMED_OUT, INTERRUPTED -> "bad";
        };
    }
}
