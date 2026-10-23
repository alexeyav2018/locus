package ru.locus.problem;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import ru.locus.assistant.Arguments;
import ru.locus.assistant.Schema;
import ru.locus.assistant.Tool;
import ru.locus.assistant.ToolResult;
import ru.locus.assistant.Tools;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.file.FilePages;
import ru.locus.file.PdfOutline;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Инструменты Помощника над Задачами общей библиотеки: поиск, чтение,
 * страница PDF картинкой и заведение из Черновиков сборки (ADR-0056;
 * locus-mcp, design.md, Р4–Р6).
 *
 * <p>Второй клиент сервисного слоя рядом с {@link ProblemController}:
 * обработчик разбирает аргументы, зовёт тот же {@link ProblemService}
 * и укладывает ответ — и больше ничего. Библиотека общая и отдаётся всем
 * вошедшим без фильтра (ADR-0027); заводить Задачу разрешает
 * {@code @PreAuthorize} сервиса, и Учитель без роли Администратора
 * получает тот же отказ, что получил бы на странице. Своей проверки прав
 * и своего репозитория у инструментов нет и быть не должно.
 *
 * <p>Наружу идут записи с плоскими полями — номер, имена, пути, —
 * а не идентификаторы-обёртки; страница — JPEG той же отрисовкой, что
 * на странице просмотра (ADR-0052). Текста у Задачи здесь ещё нет:
 * {@code create_problem} заводит её из строк сборки, как форма
 * (design.md, Р4); текст придёт с {@code problem-text}.
 *
 * <p>Аргументы читаются до первого обращения к сервису: отказ за
 * недостающий аргумент называет аргумент, а не Задачу, которой заодно
 * не оказалось.
 */
@Component
public class ProblemTools implements Tools {

    private final ProblemService problems;
    private final FilePages filePages;

    public ProblemTools(ProblemService problems, FilePages filePages) {
        this.problems = problems;
        this.filePages = filePages;
    }

    @Override
    public List<Tool> tools() {
        return List.of(findProblems(), getProblem(), getProblemPage(), createProblem());
    }

    /** Поиск — те же условия и те же правила соединения, что у страницы поиска (ADR-0031). */
    private Tool findProblems() {
        return new Tool("find_problems",
                "Найти Задачи общей библиотеки по разметке: узел рубрикатора (с обходом всего "
                        + "его поддерева), Методы, Характеристики и Часть ЕГЭ. Каждое условие "
                        + "необязательно, заданные соединяются по «и»; без условий — вся библиотека. "
                        + "Ответ — список Задач: номер, Часть, Темы полными путями, имена Методов "
                        + "и Характеристик. Подробности одной Задачи — get_problem.",
                Schema.object()
                        .property("node", Schema.integer(
                                "идентификатор узла рубрикатора — Темы из list_topics или Раздела над ней; "
                                        + "ищется всё его поддерево"))
                        .property("methods", Schema.array("идентификаторы Методов из list_methods",
                                Schema.integer("идентификатор Метода")))
                        .property("methods_match", Schema.oneOf(
                                "как соединять Методы: all — Задача размечена каждым из них, "
                                        + "any — хотя бы одним; по умолчанию any",
                                "all", "any"))
                        .property("characteristics", Schema.array(
                                "идентификаторы Характеристик из list_characteristics",
                                Schema.integer("идентификатор Характеристики")))
                        .property("characteristics_match", Schema.oneOf(
                                "как соединять Характеристики: all — Задача размечена каждой, "
                                        + "any — хотя бы одной; по умолчанию any",
                                "all", "any"))
                        .property("part", part())
                        .build(),
                arguments -> {
                    ProblemFilter filter = new ProblemFilter(
                            arguments.optionalLong("node").map(TaxonomyNodeId::new).orElse(null),
                            methodIds(arguments),
                            arguments.optionalEnum("methods_match", MatchMode.class).orElse(null),
                            characteristicIds(arguments),
                            arguments.optionalEnum("characteristics_match", MatchMode.class).orElse(null),
                            arguments.optionalEnum("part", ExamPart.class).orElse(null));
                    return ToolResult.json(problems.search(filter).stream().map(ProblemTools::found).toList());
                });
    }

    private Tool getProblem() {
        return new Tool("get_problem",
                "Задача по номеру: Часть, Темы с идентификаторами и путями, Методы "
                        + "и Характеристики с идентификаторами и именами, число страниц PDF условия "
                        + "и PDF решения. Сами страницы — get_problem_page.",
                Schema.object()
                        .property("number", Schema.integer("номер Задачи"))
                        .required("number")
                        .build(),
                arguments -> {
                    ProblemId id = new ProblemId(arguments.requireLong("number"));
                    FoundProblem found = problems.problems(List.of(id)).getFirst();
                    Problem problem = found.problem();
                    return ToolResult.json(new Detail(problem.number(), problem.part().name(),
                            zip(problem.topics(), found.topicPaths(),
                                    (topic, path) -> new TopicRef(topic.value(), path)),
                            zip(problem.methods(), found.methodNames(),
                                    (method, name) -> new EntryRef(method.value(), name)),
                            zip(problem.characteristics(), found.characteristicNames(),
                                    (characteristic, name) -> new EntryRef(characteristic.value(), name)),
                            pages(problem.conditionFile()), pages(problem.solutionFile())));
                });
    }

    private Tool getProblemPage() {
        return new Tool("get_problem_page",
                "Страница PDF условия или решения Задачи картинкой JPEG — той же отрисовкой, "
                        + "что на странице просмотра. Число страниц — в get_problem.",
                Schema.object()
                        .property("number", Schema.integer("номер Задачи"))
                        .property("slot", Schema.oneOf("какой PDF: condition — условие, solution — решение",
                                "condition", "solution"))
                        .property("page", Schema.integer("номер страницы, с единицы"))
                        .required("number", "slot", "page")
                        .build(),
                arguments -> {
                    long number = arguments.requireLong("number");
                    Slot slot = arguments.requireEnum("slot", Slot.class);
                    int page = arguments.requireInt("page");
                    Problem problem = problems.problem(new ProblemId(number));
                    return filePages.page(slot.fileOf(problem), page)
                            .map(ToolResult::image)
                            .orElse(ToolResult.refusal("В PDF " + slot.title + " Задачи № " + number
                                    + " нет страницы " + page));
                });
    }

    /** Заведение — тот же {@link ProblemService#create}, что у формы; права проверяет он. */
    private Tool createProblem() {
        return new Tool("create_problem",
                "Завести Задачу в общую библиотеку (только Администратор): Часть, Темы, Методы, "
                        + "Характеристики и два PDF — условие и решение, — собранные из Черновиков "
                        + "сборки вызывающего: страницы «с … по …» целиком либо кусок одной страницы "
                        + "по Рамке в долях. Проверки и результат те же, что у формы Задачи; "
                        + "использованные черновики удаляются. Ответ — номер заведённой Задачи.",
                Schema.object()
                        .property("part", part())
                        .property("topics", Schema.array(
                                "идентификаторы Тем из list_topics, хотя бы одна; Раздел не годится",
                                Schema.integer("идентификатор Темы")))
                        .property("methods", Schema.array(
                                "идентификаторы Методов из list_methods, хотя бы один",
                                Schema.integer("идентификатор Метода")))
                        .property("characteristics", Schema.array(
                                "идентификаторы Характеристик из list_characteristics; можно не указывать",
                                Schema.integer("идентификатор Характеристики")))
                        .property("condition", Schema.array(
                                "порядок сборки PDF условия: строки в порядке страниц результата", line()))
                        .property("solution", Schema.array(
                                "порядок сборки PDF решения: строки в порядке страниц результата", line()))
                        .required("part", "topics", "methods", "condition", "solution")
                        .build(),
                arguments -> {
                    ProblemId created = problems.create(
                            arguments.optionalEnum("part", ExamPart.class).orElse(null),
                            arguments.longs("topics").stream().map(TaxonomyNodeId::new).toList(),
                            methodIds(arguments),
                            characteristicIds(arguments),
                            order(arguments, "condition"),
                            order(arguments, "solution"));
                    return ToolResult.json(new Created(created.value()));
                });
    }

    private static Map<String, Object> part() {
        return Schema.oneOf("Часть ЕГЭ: FIRST — первая, SECOND — вторая", "FIRST", "SECOND");
    }

    private static Map<String, Object> line() {
        return Schema.object()
                .description("строка сборки: диапазон целых страниц Черновика сборки либо, с Рамкой, "
                        + "кусок одной его страницы — отдельной страницей результата; без Рамки "
                        + "страницы берутся целиком")
                .property("draft", Schema.integer("идентификатор Черновика сборки из list_assembly_drafts"))
                .property("from", Schema.integer("первая страница диапазона, с единицы; у картинки — 1"))
                .property("to", Schema.integer(
                        "последняя страница диапазона, с единицы; у картинки — 1; с Рамкой равна from"))
                .property("frame", AssemblyDraftTools.frameSchema())
                .required("draft", "from", "to")
                .build();
    }

    private static PdfAssemblyOrder order(Arguments arguments, String slot) {
        return new PdfAssemblyOrder(arguments.objects(slot).stream().map(ProblemTools::line).toList());
    }

    private static PdfAssemblyOrder.Line line(Arguments line) {
        return new PdfAssemblyOrder.Line(new AssemblyDraftId(line.requireLong("draft")),
                line.requireInt("from"), line.requireInt("to"),
                line.optionalObject("frame").map(AssemblyDraftTools::frame).orElse(null));
    }

    private static List<SolutionMethodId> methodIds(Arguments arguments) {
        return arguments.longs("methods").stream().map(SolutionMethodId::new).toList();
    }

    private static List<CharacteristicId> characteristicIds(Arguments arguments) {
        return arguments.longs("characteristics").stream().map(CharacteristicId::new).toList();
    }

    /** Число страниц PDF; файл, который не разбирается, страниц модели не даст — ноль. */
    private int pages(FileKey key) {
        return filePages.outline(key).map(PdfOutline::pages).orElse(0);
    }

    private static Found found(FoundProblem found) {
        return new Found(found.number(), found.problem().part().name(),
                found.topicPaths(), found.methodNames(), found.characteristicNames());
    }

    /** Разметка идентификаторами и именами идёт в одном порядке — складывается парами. */
    private static <I, R> List<R> zip(List<I> ids, List<String> names, BiFunction<I, String, R> pair) {
        return IntStream.range(0, ids.size()).mapToObj(i -> pair.apply(ids.get(i), names.get(i))).toList();
    }

    /** Слот PDF Задачи, как его называет модель. */
    private enum Slot {
        CONDITION("условия"),
        SOLUTION("решения");

        private final String title;

        Slot(String title) {
            this.title = title;
        }

        FileKey fileOf(Problem problem) {
            return this == CONDITION ? problem.conditionFile() : problem.solutionFile();
        }
    }

    /** Строка результата поиска: номер, Часть и разметка именами. */
    public record Found(long number,
                        String part,
                        List<String> topics,
                        List<String> methods,
                        List<String> characteristics) {
    }

    /** Задача целиком: разметка с идентификаторами и число страниц обоих PDF. */
    public record Detail(long number,
                         String part,
                         List<TopicRef> topics,
                         List<EntryRef> methods,
                         List<EntryRef> characteristics,
                         int conditionPages,
                         int solutionPages) {
    }

    /** Тема разметки: идентификатор и путь от корня. */
    public record TopicRef(long id, String path) {
    }

    /** Запись словаря в разметке: идентификатор и имя. */
    public record EntryRef(long id, String name) {
    }

    /** Ответ заведения: номер новой Задачи. */
    public record Created(long number) {
    }
}
