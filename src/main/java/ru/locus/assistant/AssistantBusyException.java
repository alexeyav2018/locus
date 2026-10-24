package ru.locus.assistant;

/**
 * Отказ начать Реплику, пока у учителя идёт другая: Реплика одна на учителя
 * (ADR-0054). Держит запрет база — частичный уникальный индекс, — а это
 * исключение переводит её отказ в слова.
 */
public class AssistantBusyException extends RuntimeException {

    public AssistantBusyException() {
        super("Помощник ещё отвечает на прошлую реплику — дождитесь ответа или остановите его");
    }
}
