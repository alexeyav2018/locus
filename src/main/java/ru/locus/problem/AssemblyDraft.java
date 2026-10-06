package ru.locus.problem;

import java.time.Instant;
import ru.locus.user.UserId;

/**
 * Черновик сборки — исходник, загруженный Администратором, чтобы собрать
 * из него PDF слота Задачи: картинка или сборник PDF (ADR-0044).
 *
 * Временный и личный: принадлежит загрузившему, живёт до сборки
 * и сущностью предметной области не является — Задача о нём ничего
 * не помнит, в хранилище ложится только собранный результат.
 *
 * @param fileName имя файла в рабочей папке черновиков; наружу не уходит
 * @param pageCount у картинки — единица: она становится одной страницей
 */
public record AssemblyDraft(AssemblyDraftId id,
                            UserId owner,
                            String originalName,
                            Kind kind,
                            String contentType,
                            int pageCount,
                            String fileName,
                            Instant createdAt) {

    /** Вид исходника: от него зависит, чем станет строка сборки. */
    public enum Kind {
        /** Картинка JPEG или PNG — ровно одна страница результата. */
        IMAGE,
        /** PDF — из него берётся диапазон целых страниц. */
        PDF
    }
}
