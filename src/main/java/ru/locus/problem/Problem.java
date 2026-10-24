package ru.locus.problem;

import java.util.List;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Задача — единица общей библиотеки: два PDF и разметка (ADR-0009).
 *
 * Задача бывает двух видов, живущих в одной библиотеке без срока (ADR-0055,
 * заменяет ADR-0008): <b>с текстом</b> — у неё есть {@link ProblemText},
 * и оба PDF Локус отрисовал из него сам; <b>без текста</b> — оба PDF
 * приложены готовыми или собраны из Черновика сборки. Вид не хранится
 * отдельно: «без текста» — это {@code text == null}. Текст Локус хранит
 * и отрисовывает, но не разбирает, поэтому вход в Задачу для поиска
 * по-прежнему разметка, а различает Задачи <b>номер</b> — он же
 * идентификатор; текст не имя. Подписи у Задачи нет (ADR-0050, заменяет
 * ADR-0029).
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
 * @param conditionFile    PDF условия: у Задачи с текстом — отрисовка текста
 * @param solutionFile     PDF решения: у Задачи с текстом — отрисовка текста
 * @param text             текст и Улики; {@code null} — Задача без текста
 * @param topics           Темы разметки, 1..n, только листья дерева;
 *                         «лист» проверяется в сервисе — вид узла не хранится
 * @param methods          Методы разметки, 1..n
 * @param characteristics  Характеристики разметки, 0..n
 */
public record Problem(ProblemId id,
                      ExamPart part,
                      FileKey conditionFile,
                      FileKey solutionFile,
                      ProblemText text,
                      List<TaxonomyNodeId> topics,
                      List<SolutionMethodId> methods,
                      List<CharacteristicId> characteristics) {

    public Problem {
        if (id == null) {
            throw new IllegalArgumentException("У Задачи должен быть идентификатор");
        }
        requireMarkup(part, topics, methods);
        requireFiles(conditionFile, solutionFile);
        topics = List.copyOf(topics);
        methods = List.copyOf(methods);
        characteristics = characteristics == null ? List.of() : List.copyOf(characteristics);
    }

    /** Задача без текста — та, у которой оба PDF приложены или собраны. */
    public Problem(ProblemId id,
                   ExamPart part,
                   FileKey conditionFile,
                   FileKey solutionFile,
                   List<TaxonomyNodeId> topics,
                   List<SolutionMethodId> methods,
                   List<CharacteristicId> characteristics) {
        this(id, part, conditionFile, solutionFile, null, topics, methods, characteristics);
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

    /** Есть ли у Задачи текст; «без текста» не хранится, а выводится отсюда. */
    public boolean hasText() {
        return text != null;
    }

    /** Номер Задачи — то, чем она названа человеку. */
    public long number() {
        return id.value();
    }
}
