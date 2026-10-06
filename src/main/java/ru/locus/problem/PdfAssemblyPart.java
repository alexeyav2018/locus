package ru.locus.problem;

import java.nio.file.Path;

/**
 * Кусок сборки PDF Задачи: картинка или диапазон целых страниц PDF
 * (ADR-0041). Из упорядоченного списка таких кусков {@link PdfAssembly}
 * собирает результат.
 *
 * Источник — файл на диске, а не байты в памяти: сборник весит десятки
 * мегабайт, и PDFBox подгружает его объекты с диска по мере надобности.
 * Имя источника — то, под которым его загрузил Администратор; оно нужно
 * только отказу, чтобы назвать, какой из источников указан неверно.
 */
public sealed interface PdfAssemblyPart {

    Path file();

    String name();

    /** Картинка JPEG или PNG — ровно одна страница результата. */
    record Image(Path file, String name) implements PdfAssemblyPart {
    }

    /**
     * Страницы PDF «с … по …», нумерация с единицы, как у Администратора
     * в просмотрщике. Что диапазон не выходит за число страниц, проверяется
     * при сборке: число страниц знает только открытый файл.
     */
    record Pages(Path file, String name, int from, int to) implements PdfAssemblyPart {

        public Pages {
            if (from < 1) {
                throw new IllegalArgumentException(
                        "Источник «" + name + "»: страницы нумеруются с первой, а указана " + from + "-я");
            }
            if (from > to) {
                throw new IllegalArgumentException(
                        "Источник «" + name + "»: начало диапазона (" + from + ") позже его конца (" + to + ")");
            }
        }
    }
}
