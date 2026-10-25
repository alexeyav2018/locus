package ru.locus.assistant;

import java.io.InputStream;

/**
 * Файл, приложенный учителем к запросу Реплики со страницы Помощника
 * (design.md assistant-chat, Р5): снимок листа или PDF. Что с ним станет,
 * решает ответчик {@link TurnAttachments}, а не Помощник.
 *
 * <p>Номер — номер поля «Файл N», в котором файл пришёл, а не место
 * в списке: пустое поле пропускается, и номера остальных не сдвигаются
 * (design.md assistant-multiple-files, Р1).
 *
 * @param number  номер поля на странице, от единицы
 * @param name    имя файла, как его назвал браузер
 * @param content содержимое; читает и закрывает вызвавший
 */
public record TurnAttachment(int number, String name, InputStream content) {

    /** Как файл зовут учитель и Помощник: «Файл N». */
    public String label() {
        return "Файл " + number;
    }
}
