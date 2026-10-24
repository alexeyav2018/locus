package ru.locus.assistant;

import java.io.InputStream;

/**
 * Файл, приложенный учителем к запросу Реплики со страницы Помощника
 * (design.md assistant-chat, Р5): снимок листа или PDF. Что с ним станет,
 * решает ответчик {@link TurnAttachments}, а не Помощник.
 *
 * @param name    имя файла, как его назвал браузер
 * @param content содержимое; читает и закрывает вызвавший
 */
public record TurnAttachment(String name, InputStream content) {
}
