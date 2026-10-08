package ru.locus.problem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Журнал отказов сборки PDF Задачи.
 *
 * <p>Администратор видит отказ текстом для человека («Картинка «…» не
 * разбирается»), а разбирать обращение со стенда нужно по исходной причине —
 * исключению разборщика, которое на экран не выводится. Пишет тот, кто
 * показывает отказ: {@link PdfAssembly} — чистая функция, и её отказ ещё
 * может быть перехвачен выше.
 *
 * <p>В журнал попадает только отказ с исходным исключением: это сбой
 * разбора файла. Отказ без причины — ответ на ввод (нет Метода, страница
 * вне диапазона), разбирать по нему нечего. Имя файла отдельно
 * не передаётся — оно уже в тексте отказа.
 */
final class AssemblyRefusals {

    private static final Logger LOG = LoggerFactory.getLogger(AssemblyRefusals.class);

    private AssemblyRefusals() {
    }

    static void report(RuntimeException refusal) {
        if (refusal.getCause() != null) {
            LOG.warn("Отказ сборки PDF: {}", refusal.getMessage(), refusal.getCause());
        }
    }
}
