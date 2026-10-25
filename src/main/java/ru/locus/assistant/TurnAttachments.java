package ru.locus.assistant;

import java.util.List;

/**
 * Вопрос, который Помощник задаёт области, умеющей принять файл: «прими
 * файл к Реплике и скажи, что передать Помощнику» (design.md
 * assistant-chat, Р5).
 *
 * <p>У процесса Помощника файлов нет (ADR-0057): файл учителя ложится
 * в Локус, а Помощник получает в запросе строку о том, где его найти
 * инструментами, — по строке на файл. Ответчик один — {@code AssemblyDraftAttachments}
 * в области Задач: файл становится Черновиком сборки вошедшего.
 *
 * <p>Направление зависимости — от области Задач к {@code ru.locus.assistant},
 * как у {@link Tools}: Помощник не знает областей ни одной, спрашивает
 * он, отвечает знающий. Образец — {@code NodeContent} и {@code StudentUsage}.
 */
public interface TurnAttachments {

    /**
     * Принимает файлы от имени вошедшего — все или ни одного; права
     * и отказы — того, кто принимает (design.md assistant-multiple-files, Р3).
     *
     * @return по строке запроса на файл, в порядке списка: каждая скажет
     *         Помощнику, где файл, и начинается с {@link TurnAttachment#label()}
     * @throws IllegalArgumentException файл не подходит — ни один не принят;
     *                                  текст начинается с {@link TurnAttachment#label()}
     *                                  отклонённого и называет причину
     */
    List<String> attach(List<TurnAttachment> files);
}
