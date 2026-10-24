package ru.locus.problem;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.locus.Addresses;
import ru.locus.assistant.Arguments;
import ru.locus.assistant.Schema;
import ru.locus.assistant.Tool;
import ru.locus.assistant.ToolResult;
import ru.locus.assistant.Tools;
import ru.locus.dictionary.Characteristic;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethod;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.problem.ProblemTools.EntryRef;
import ru.locus.problem.ProblemTools.TopicRef;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyPath;
import ru.locus.taxonomy.TaxonomyService;

/**
 * Инструменты Помощника над Черновиками Задачи вызывающего: завести,
 * перечислить, прочитать, записать, сохранить Задачей и удалить
 * (ADR-0059; problem-intake, design.md, Р7).
 *
 * <p>Второй клиент того же {@link ProblemDraftService}, что страница вычитки
 * {@link ProblemDraftController}: обработчик разбирает аргументы, зовёт
 * сервис и укладывает ответ. Права и владельца проверяет сервис — Учитель
 * без роли Администратора получает тот же отказ, что на странице, а чужой
 * черновик неотличим от несуществующего. Кто правил, инструмент передаёт
 * константой {@link ProblemDraft.Editor#ASSISTANT} (Р3): страница вычитки
 * по ней отмечает «изменён Помощником».
 *
 * <p>{@code update_problem_draft} пишет только названное: отсутствующий
 * аргумент — «не трогать», пустая строка или пустой список — «очистить».
 * Запись, сохранение и удаление несут версию прочитанного; ушедшая вперёд
 * хранимая отклоняет вызов текстом сервиса «перечитайте» — правка учителя
 * поверх не затирается.
 *
 * <p>Ответ — плоские поля; разметка — идентификаторы с именами, чтобы
 * модели не нужно было сверять их со словарями ещё одним вызовом.
 */
@Component
public class ProblemDraftTools implements Tools {

    private final ProblemDraftService intake;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;

    public ProblemDraftTools(ProblemDraftService intake,
                             TaxonomyService taxonomy,
                             SolutionMethodService methods,
                             CharacteristicService characteristics) {
        this.intake = intake;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
    }

    @Override
    public List<Tool> tools() {
        return List.of(listProblemDrafts(), createProblemDraft(), getProblemDraft(),
                updateProblemDraft(), saveProblemDraft(), deleteProblemDraft());
    }

    private Tool listProblemDrafts() {
        return new Tool("list_problem_drafts",
                "Свои Черновики Задачи (только Администратор): идентификатор, версия, кто правил "
                        + "последним — assistant или teacher — и когда, адрес страницы вычитки. "
                        + "Черновик целиком — get_problem_draft.",
                Schema.object().build(),
                arguments -> ToolResult.json(intake.drafts().stream().map(ProblemDraftTools::summary).toList()));
    }

    private Tool createProblemDraft() {
        return new Tool("create_problem_draft",
                "Завести Черновик Задачи (только Администратор) — распознанную Задачу, которую "
                        + "учитель вычитает на странице и утвердит: тексты условия и решения, строки "
                        + "сборки Улик из своих Черновиков сборки, сомнительные места, предложенные "
                        + "Часть, Темы, Методы и Характеристики, имена новых Методов. Всё необязательно. "
                        + "Ответ — черновик целиком: идентификатор, версия и адрес страницы вычитки.",
                content(Schema.object()).build(),
                arguments -> ToolResult.json(detail(intake.create(new ProblemDraftContent(
                        arguments.optionalEnum("part", ExamPart.class).orElse(null),
                        arguments.optionalText("condition_text").orElse(""),
                        arguments.optionalText("solution_text").orElse(""),
                        ProblemTools.order(arguments, "condition"),
                        ProblemTools.order(arguments, "solution"),
                        arguments.strings("doubts"),
                        topicIds(arguments),
                        methodIds(arguments),
                        characteristicIds(arguments),
                        arguments.strings("new_methods")), ProblemDraft.Editor.ASSISTANT))));
    }

    private Tool getProblemDraft() {
        return new Tool("get_problem_draft",
                "Свой Черновик Задачи целиком с версией: тексты, строки сборки, сомнительные места, "
                        + "разметка с именами, новые Методы, кто и когда правил последним. Версию "
                        + "передают update_problem_draft, save_problem_draft и delete_problem_draft.",
                Schema.object()
                        .property("id", id())
                        .required("id")
                        .build(),
                arguments -> ToolResult.json(detail(intake.draft(draftId(arguments)))));
    }

    private Tool updateProblemDraft() {
        return new Tool("update_problem_draft",
                "Записать свой Черновик Задачи с версией прочитанного. Пишется только названное: "
                        + "часть, которой нет в вызове, остаётся прежней; пустая строка или пустой "
                        + "список её очищают. Если черновик изменён после чтения — например, учитель "
                        + "поправил его на странице, — вызов отклоняется: перечитайте get_problem_draft. "
                        + "Ответ — черновик целиком с новой версией.",
                content(Schema.object()
                        .property("id", id())
                        .property("version", version()))
                        .required("id", "version")
                        .build(),
                arguments -> ToolResult.json(detail(intake.write(draftId(arguments),
                        arguments.requireLong("version"),
                        new ProblemDraftChange(
                                arguments.has("part") ? arguments.optionalEnum("part", ExamPart.class) : null,
                                arguments.optionalText("condition_text").orElse(null),
                                arguments.optionalText("solution_text").orElse(null),
                                arguments.has("condition") ? ProblemTools.order(arguments, "condition") : null,
                                arguments.has("solution") ? ProblemTools.order(arguments, "solution") : null,
                                arguments.has("doubts") ? arguments.strings("doubts") : null,
                                arguments.has("topics") ? topicIds(arguments) : null,
                                arguments.has("methods") ? methodIds(arguments) : null,
                                arguments.has("characteristics") ? characteristicIds(arguments) : null,
                                arguments.has("new_methods") ? arguments.strings("new_methods") : null),
                        ProblemDraft.Editor.ASSISTANT))));
    }

    private Tool saveProblemDraft() {
        return new Tool("save_problem_draft",
                "Сохранить свой Черновик Задачи как Задачу с текстом (только Администратор), "
                        + "с версией прочитанного: тексты без пометок сомнений, PDF отрисованы "
                        + "Локусом, строки сборки — Улики. Проверки те же, что у формы Задачи: Часть, "
                        + "хотя бы одна Тема и один Метод, формулы разбираются. Черновик удаляется. "
                        + "Ответ — номер заведённой Задачи.",
                Schema.object()
                        .property("id", id())
                        .property("version", version())
                        .required("id", "version")
                        .build(),
                arguments -> ToolResult.json(new Saved(intake.save(draftId(arguments),
                        arguments.requireLong("version"), ProblemDraftService.Kind.TEXT).value())));
    }

    private Tool deleteProblemDraft() {
        return new Tool("delete_problem_draft",
                "Удалить свой Черновик Задачи с версией прочитанного; Задача не заводится.",
                Schema.object()
                        .property("id", id())
                        .property("version", version())
                        .required("id", "version")
                        .build(),
                arguments -> {
                    ProblemDraftId id = draftId(arguments);
                    intake.delete(id, arguments.requireLong("version"));
                    return ToolResult.json(new Deleted(id.value()));
                });
    }

    /** Части содержимого — одни у заведения и у записи. */
    private static Schema.ObjectSchema content(Schema.ObjectSchema schema) {
        return schema
                .property("condition_text", Schema.string(
                        "текст условия: Markdown, формулы LaTeX $…$ и $$…$$; сомнительное место "
                                + "обводится пометкой ⟦…⟧"))
                .property("solution_text", Schema.string("текст решения, как условие"))
                .property("condition", Schema.array(
                        "строки сборки Улики условия — снимка, с которого набран текст", ProblemTools.line()))
                .property("solution", Schema.array("строки сборки Улики решения", ProblemTools.line()))
                .property("doubts", Schema.array("причины сомнений, по строке на пометку ⟦…⟧ в порядке текста",
                        Schema.string("причина сомнения")))
                .property("part", Schema.oneOf(
                        "Часть ЕГЭ: FIRST — первая, SECOND — вторая; пустая строка — Часть не предложена",
                        "FIRST", "SECOND", ""))
                .property("topics", Schema.array("идентификаторы Тем из list_topics; Раздел не годится",
                        Schema.integer("идентификатор Темы")))
                .property("methods", Schema.array("идентификаторы Методов из list_methods",
                        Schema.integer("идентификатор Метода")))
                .property("characteristics", Schema.array(
                        "идентификаторы Характеристик из list_characteristics",
                        Schema.integer("идентификатор Характеристики")))
                .property("new_methods", Schema.array(
                        "имена Методов, которых в словаре нет, — предложение учителю; в словарь "
                                + "они не попадают",
                        Schema.string("имя Метода")));
    }

    private static Map<String, Object> id() {
        return Schema.integer("идентификатор Черновика Задачи из list_problem_drafts или create_problem_draft");
    }

    private static Map<String, Object> version() {
        return Schema.integer("версия черновика из последнего прочитанного ответа");
    }

    private static ProblemDraftId draftId(Arguments arguments) {
        return new ProblemDraftId(arguments.requireLong("id"));
    }

    private static List<TaxonomyNodeId> topicIds(Arguments arguments) {
        return arguments.longs("topics").stream().map(TaxonomyNodeId::new).toList();
    }

    private static List<SolutionMethodId> methodIds(Arguments arguments) {
        return arguments.longs("methods").stream().map(SolutionMethodId::new).toList();
    }

    private static List<CharacteristicId> characteristicIds(Arguments arguments) {
        return arguments.longs("characteristics").stream().map(CharacteristicId::new).toList();
    }

    private static Summary summary(ProblemDraft draft) {
        return new Summary(draft.id().value(), draft.version(), editor(draft), draft.changedAt().toString(),
                link(draft));
    }

    private Detail detail(ProblemDraft draft) {
        ProblemDraftContent content = draft.content();
        Map<TaxonomyNodeId, String> paths = names(taxonomy.topicPaths(), TaxonomyPath::id, TaxonomyPath::path);
        Map<SolutionMethodId, String> methodNames = names(methods.all(), SolutionMethod::id, SolutionMethod::name);
        Map<CharacteristicId, String> characteristicNames =
                names(characteristics.all(), Characteristic::id, Characteristic::name);
        return new Detail(draft.id().value(), draft.version(), editor(draft), draft.changedAt().toString(),
                content.conditionText(), content.solutionText(),
                lines(content.condition()), lines(content.solution()),
                content.doubts(),
                Optional.ofNullable(content.part()).map(ExamPart::name).orElse(null),
                content.topics().stream().map(topic -> new TopicRef(topic.value(), paths.get(topic))).toList(),
                content.methods().stream()
                        .map(method -> new EntryRef(method.value(), methodNames.get(method))).toList(),
                content.characteristics().stream()
                        .map(characteristic -> new EntryRef(characteristic.value(),
                                characteristicNames.get(characteristic))).toList(),
                content.newMethods(),
                link(draft));
    }

    private static <T, I> Map<I, String> names(List<T> entries, Function<T, I> id, Function<T, String> name) {
        return entries.stream().collect(Collectors.toMap(id, name));
    }

    private static List<Line> lines(PdfAssemblyOrder order) {
        return order.lines().stream()
                .map(line -> new Line(line.draft().value(), line.from(), line.to(),
                        line.frame() == null ? null : new Frame(line.frame().left(), line.frame().top(),
                                line.frame().width(), line.frame().height())))
                .toList();
    }

    /** Кто правил, как его называет модель. */
    private static String editor(ProblemDraft draft) {
        return draft.changedBy() == ProblemDraft.Editor.ASSISTANT ? "assistant" : "teacher";
    }

    private static String link(ProblemDraft draft) {
        return Addresses.PROBLEM_INTAKE + "/" + draft.id().value();
    }

    /** Строка списка черновиков. */
    public record Summary(long id, long version, String changedBy, String changedAt, String link) {
    }

    /** Черновик целиком. */
    public record Detail(long id,
                         long version,
                         String changedBy,
                         String changedAt,
                         String conditionText,
                         String solutionText,
                         List<Line> condition,
                         List<Line> solution,
                         List<String> doubts,
                         String part,
                         List<TopicRef> topics,
                         List<EntryRef> methods,
                         List<EntryRef> characteristics,
                         List<String> newMethods,
                         String link) {
    }

    /** Строка сборки: Черновик сборки, страницы и Рамка, если взят кусок. */
    public record Line(long draft, int from, int to, Frame frame) {
    }

    /** Рамка долями страницы — так же, как её принимают аргументы. */
    public record Frame(double left, double top, double width, double height) {
    }

    /** Ответ сохранения: номер заведённой Задачи. */
    public record Saved(long number) {
    }

    /** Ответ удаления: идентификатор удалённого черновика. */
    public record Deleted(long id) {
    }
}
