package ru.locus.assistant;

import java.util.Optional;

/**
 * Вопрос страницы Помощника к области, где ждёт вычитки сделанное им:
 * «что у вошедшего ждёт вычитки и где это смотреть» (problem-intake,
 * design.md, Р6). Помощник заводит черновик, а утверждает его учитель
 * на странице вычитки — ссылка на очередь стоит там, где он говорит
 * с Помощником.
 *
 * <p>Направление зависимости — от области к {@code ru.locus.assistant},
 * как у {@link TurnAttachments}: Помощник не знает областей, спрашивает
 * он, отвечает знающий. Ответчик один — {@code ProblemDraftQueue}
 * в области Задач.
 */
public interface AwaitingReview {

    /**
     * Очередь вычитки вошедшего; пусто — показывать нечего: у вошедшего
     * нет права её вести.
     */
    Optional<Queue> awaiting();

    /**
     * Ссылка на очередь вычитки.
     *
     * @param label   чем ссылка названа, без числа
     * @param address внутренний адрес списка
     * @param count   сколько в очереди сейчас
     */
    record Queue(String label, String address, int count) {
    }
}
