package ru.locus.problem;

import java.util.List;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Задача — единица общей библиотеки: два PDF и разметка (ADR-0008, ADR-0009).
 *
 * Текста Задачи система не знает и знать не должна: формулы живут внутри PDF,
 * система их не хранит и не разбирает. Поэтому разметка — единственный вход
 * в Задачу, а различают Задачи <b>номер</b> (он же идентификатор) и
 * необязательная <b>подпись</b> (ADR-0029).
 *
 * Правила состава стоят в компактном конструкторе, а не в сервисе: запись,
 * собранная в обход правил, не должна существовать вообще — ни в памяти,
 * ни тем более в базе. Требование «хотя бы один Метод» здесь не формальность:
 * отметка Владения живёт на паре «Тема × Метод» (ADR-0011), и Задача без
 * Методов не порождает ни одной ячейки, то есть остаётся немой для
 * статистики — а обнаружилось бы это через полгода, по пустым ячейкам.
 *
 * Задача принадлежит общей библиотеке: владельца у неё нет и быть не должно
 * (ADR-0027).
 *
 * @param caption          подпись; {@code null} — законное состояние, тогда
 *                         Задача различается номером
 * @param topics           Темы разметки, 1..n, только листья дерева;
 *                         «лист» проверяется в сервисе — вид узла не хранится
 * @param methods          Методы разметки, 1..n
 * @param characteristics  Характеристики разметки, 0..n
 */
public record Problem(ProblemId id,
                      String caption,
                      ExamPart part,
                      FileKey conditionFile,
                      FileKey solutionFile,
                      List<TaxonomyNodeId> topics,
                      List<SolutionMethodId> methods,
                      List<CharacteristicId> characteristics) {

    public Problem {
        if (id == null) {
            throw new IllegalArgumentException("У Задачи должен быть идентификатор");
        }
        caption = normalizedCaption(caption);
        requireMarkup(part, topics, methods);
        requireFiles(conditionFile, solutionFile);
        topics = List.copyOf(topics);
        methods = List.copyOf(methods);
        characteristics = characteristics == null ? List.of() : List.copyOf(characteristics);
    }

    /**
     * Проверка состава разметки, вынесенная из конструктора отдельно, — ею
     * пользуется и {@link ProblemService} до того, как Задача заведена
     * и записи ещё нет.
     *
     * Одна проверка на оба случая, а не две одинаковые: разойдясь, они дали бы
     * Задачу, которая сохраняется, но не читается обратно.
     */
    static void requireMarkup(ExamPart part,
                              List<TaxonomyNodeId> topics,
                              List<SolutionMethodId> methods) {
        if (part == null) {
            throw new IllegalArgumentException("У Задачи должна быть указана Часть ЕГЭ");
        }
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException("Задаче нужна хотя бы одна Тема");
        }
        if (methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("Задаче нужен хотя бы один Метод");
        }
    }

    /**
     * Оба файла обязательны. Задача без решения обнаружит свою неполноту
     * только в момент проверки Работы ученика, когда решение понадобится,
     * а искать его будет поздно (ADR-0008).
     */
    static void requireFiles(FileKey conditionFile, FileKey solutionFile) {
        if (conditionFile == null) {
            throw new IllegalArgumentException("У Задачи нет PDF условия");
        }
        if (solutionFile == null) {
            throw new IllegalArgumentException("У Задачи нет PDF решения");
        }
    }

    /** Номер Задачи — то, чем она названа человеку. */
    public long number() {
        return id.value();
    }

    /** Есть ли подпись: пустая — законное состояние, тогда остаётся номер. */
    public boolean hasCaption() {
        return caption != null;
    }

    /**
     * Пустая подпись и подпись из одних пробелов — это отсутствие подписи,
     * а не подпись. Иначе в списке появилась бы строка, выглядящая
     * подписанной, но пустая на вид.
     */
    private static String normalizedCaption(String caption) {
        if (caption == null) {
            return null;
        }
        String trimmed = caption.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
