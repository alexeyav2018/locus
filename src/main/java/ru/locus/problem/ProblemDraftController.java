package ru.locus.problem;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;

/**
 * Черновики Задачи: список и страница вычитки (problem-intake, design.md, Р6).
 *
 * <p>Контроллер тонкий: разбор полей и выбор шаблона. Права и владельца
 * проверяет {@link ProblemDraftService} на каждом действии, поэтому чужой
 * черновик — 404 и по ссылке, и по прямому адресу.
 *
 * <p>Форма одна, кнопок три ({@code formaction}): «Сохранить черновик»,
 * «Сохранить как Задачу» и «Взять снимок как есть». Все три шлют введённое
 * и прочитанную версию. Утверждение — два вызова сервиса: сначала запись
 * введённого, затем заведение Задачи с выросшей версией (Р4), — отказ
 * заведения не откатывает записанное.
 *
 * <p>Кем правлен черновик, контроллер передаёт константой
 * {@link ProblemDraft.Editor#OWNER} (Р3): на странице правит вошедший.
 */
@Controller
public class ProblemDraftController {

    private static final int TITLE_LENGTH = 80;

    private final ProblemDraftService intake;
    private final AssemblyDraftService assemblyDrafts;
    private final ProblemService problems;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;
    private final Clock clock;

    public ProblemDraftController(ProblemDraftService intake,
                                  AssemblyDraftService assemblyDrafts,
                                  ProblemService problems,
                                  TaxonomyService taxonomy,
                                  SolutionMethodService methods,
                                  CharacteristicService characteristics,
                                  Clock clock) {
        this.intake = intake;
        this.assemblyDrafts = assemblyDrafts;
        this.problems = problems;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
        this.clock = clock;
    }

    /** Свои черновики; открытие списка попутно убирает брошенные (Р4). */
    @GetMapping(Addresses.PROBLEM_INTAKE)
    public String list(Model model) {
        model.addAttribute("drafts", intake.drafts().stream()
                .map(draft -> new Listed(draft, local(draft), title(draft.content())))
                .toList());
        return "problem/intake-list";
    }

    /** Страница вычитки: Улика рядом с отрисовкой, поля правки, разметка. */
    @GetMapping(Addresses.PROBLEM_INTAKE + "/{id}")
    public String draft(@PathVariable long id, Model model) {
        ProblemDraft draft = intake.draft(new ProblemDraftId(id));
        return page(draft, draft.content(), model);
    }

    /** «Сохранить черновик»: запись введённого и снова страница вычитки. */
    @PostMapping(Addresses.PROBLEM_INTAKE + "/{id}")
    public String write(@PathVariable long id,
                        @RequestParam long version,
                        @RequestParam(required = false) ExamPart part,
                        @RequestParam(required = false) List<Long> topics,
                        @RequestParam(required = false) List<Long> methodIds,
                        @RequestParam(required = false) List<Long> characteristicIds,
                        @RequestParam(required = false) String conditionText,
                        @RequestParam(required = false) String solutionText,
                        @RequestParam(required = false) List<Long> conditionDraft,
                        @RequestParam(required = false) List<Integer> conditionFrom,
                        @RequestParam(required = false) List<Integer> conditionTo,
                        @RequestParam(required = false) List<String> conditionCrop,
                        @RequestParam(required = false) List<Long> solutionDraft,
                        @RequestParam(required = false) List<Integer> solutionFrom,
                        @RequestParam(required = false) List<Integer> solutionTo,
                        @RequestParam(required = false) List<String> solutionCrop,
                        Model model) {
        Sent sent = new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                conditionDraft, conditionFrom, conditionTo, conditionCrop,
                solutionDraft, solutionFrom, solutionTo, solutionCrop);
        ProblemDraftId draftId = new ProblemDraftId(id);
        try {
            written(draftId, version, sent);
            return "redirect:" + Addresses.PROBLEM_INTAKE + "/" + id;
        } catch (ProblemDraftChangedException refusal) {
            return refusedStored(draftId, refusal, model);
        } catch (NotATopicException | IllegalArgumentException refusal) {
            return refusedSent(draftId, sent, refusal, model);
        }
    }

    /** «Сохранить как Задачу»: Задача с текстом черновика без пометок. */
    @PostMapping(Addresses.PROBLEM_INTAKE + "/{id}/problem")
    public String saveAsProblem(@PathVariable long id,
                                @RequestParam long version,
                                @RequestParam(required = false) ExamPart part,
                                @RequestParam(required = false) List<Long> topics,
                                @RequestParam(required = false) List<Long> methodIds,
                                @RequestParam(required = false) List<Long> characteristicIds,
                                @RequestParam(required = false) String conditionText,
                                @RequestParam(required = false) String solutionText,
                                @RequestParam(required = false) List<Long> conditionDraft,
                                @RequestParam(required = false) List<Integer> conditionFrom,
                                @RequestParam(required = false) List<Integer> conditionTo,
                                @RequestParam(required = false) List<String> conditionCrop,
                                @RequestParam(required = false) List<Long> solutionDraft,
                                @RequestParam(required = false) List<Integer> solutionFrom,
                                @RequestParam(required = false) List<Integer> solutionTo,
                                @RequestParam(required = false) List<String> solutionCrop,
                                Model model) {
        return saved(new ProblemDraftId(id), version, ProblemDraftService.Kind.TEXT,
                new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                        conditionDraft, conditionFrom, conditionTo, conditionCrop,
                        solutionDraft, solutionFrom, solutionTo, solutionCrop),
                model);
    }

    /** «Взять снимок как есть»: Задача без текста, PDF слотов — из строк сборки. */
    @PostMapping(Addresses.PROBLEM_INTAKE + "/{id}/snapshot")
    public String saveSnapshot(@PathVariable long id,
                               @RequestParam long version,
                               @RequestParam(required = false) ExamPart part,
                               @RequestParam(required = false) List<Long> topics,
                               @RequestParam(required = false) List<Long> methodIds,
                               @RequestParam(required = false) List<Long> characteristicIds,
                               @RequestParam(required = false) String conditionText,
                               @RequestParam(required = false) String solutionText,
                               @RequestParam(required = false) List<Long> conditionDraft,
                               @RequestParam(required = false) List<Integer> conditionFrom,
                               @RequestParam(required = false) List<Integer> conditionTo,
                               @RequestParam(required = false) List<String> conditionCrop,
                               @RequestParam(required = false) List<Long> solutionDraft,
                               @RequestParam(required = false) List<Integer> solutionFrom,
                               @RequestParam(required = false) List<Integer> solutionTo,
                               @RequestParam(required = false) List<String> solutionCrop,
                               Model model) {
        return saved(new ProblemDraftId(id), version, ProblemDraftService.Kind.SNAPSHOT,
                new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                        conditionDraft, conditionFrom, conditionTo, conditionCrop,
                        solutionDraft, solutionFrom, solutionTo, solutionCrop),
                model);
    }

    /** «Удалить черновик»: Задачи не заводит, ведёт к списку черновиков. */
    @PostMapping(Addresses.PROBLEM_INTAKE + "/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam long version, Model model) {
        ProblemDraftId draftId = new ProblemDraftId(id);
        try {
            intake.delete(draftId, version);
            return "redirect:" + Addresses.PROBLEM_INTAKE;
        } catch (ProblemDraftChangedException refusal) {
            return refusedStored(draftId, refusal, model);
        }
    }

    /**
     * Утверждение: сначала запись введённого своим вызовом, затем заведение
     * Задачи с выросшей версией (Р4). Отказ записи — как у «Сохранить
     * черновик»; отказ заведения — хранимый черновик с отказом: введённое
     * уже записано.
     */
    private String saved(ProblemDraftId id, long version, ProblemDraftService.Kind kind, Sent sent, Model model) {
        ProblemDraft written;
        try {
            written = written(id, version, sent);
        } catch (ProblemDraftChangedException refusal) {
            return refusedStored(id, refusal, model);
        } catch (NotATopicException | IllegalArgumentException refusal) {
            return refusedSent(id, sent, refusal, model);
        }
        try {
            ProblemId created = intake.save(id, written.version(), kind);
            return "redirect:" + Addresses.PROBLEMS + "/" + created.value();
        } catch (ProblemDraftChangedException | NotATopicException | IllegalArgumentException refusal) {
            return refusedStored(id, refusal, model);
        }
    }

    private ProblemDraft written(ProblemDraftId id, long version, Sent sent) {
        AssemblyOrderForm.requireFrames(sent.conditionCrop());
        AssemblyOrderForm.requireFrames(sent.solutionCrop());
        return intake.write(id, version, sent.change(), ProblemDraft.Editor.OWNER);
    }

    /**
     * Отказ с хранимым черновиком: отказ версии — введённое писано
     * по устаревшему, и молча смешать его с чужой правкой нельзя (Р6);
     * отказ заведения — введённое уже записано.
     */
    private String refusedStored(ProblemDraftId id, RuntimeException refusal, Model model) {
        AssemblyRefusals.report(refusal);
        ProblemDraft stored = intake.draft(id);
        String page = page(stored, stored.content(), model);
        model.addAttribute("error", refusal.getMessage());
        return page;
    }

    /**
     * Отказ записи разметкой или строкой: черновик прежний, а в полях —
     * присланное, чтобы человек не набирал его заново. Версия — хранимая:
     * она и есть прочитанная.
     */
    private String refusedSent(ProblemDraftId id, Sent sent, RuntimeException refusal, Model model) {
        AssemblyRefusals.report(refusal);
        ProblemDraft stored = intake.draft(id);
        String page = page(stored, sent.change().applyTo(stored.content()), model);
        model.addAttribute("error", refusal.getMessage());
        return page;
    }

    /**
     * Модель страницы вычитки. Показывается {@code shown} — хранимое
     * или присланное до отказа; версия и отметка правки — хранимого.
     */
    private String page(ProblemDraft stored, ProblemDraftContent shown, Model model) {
        ProblemDraft draft = new ProblemDraft(stored.id(), stored.owner(), shown, stored.version(),
                stored.changedBy(), stored.changedAt(), stored.createdAt());
        ProblemForm form = ProblemForm.sent(shown.part(),
                shown.topics().stream().map(TaxonomyNodeId::value).toList(),
                shown.methods().stream().map(SolutionMethodId::value).toList(),
                shown.characteristics().stream().map(CharacteristicId::value).toList(),
                ProblemForm.Kind.TEXT, shown.conditionText(), shown.solutionText());
        Long topic = form.firstTopic();
        model.addAttribute("draft", draft);
        model.addAttribute("changedAt", local(stored));
        model.addAttribute("form", form);
        model.addAttribute("evidence", intake.evidence(draft));
        model.addAttribute("rendering", intake.rendering(draft));
        model.addAttribute("conditionDoubts", DoubtMarks.fragments(shown.conditionText()));
        model.addAttribute("solutionDoubts", DoubtMarks.fragments(shown.solutionText()));
        model.addAttribute("conditionRows", assemblyDrafts.rows(shown.condition()));
        model.addAttribute("solutionRows", assemblyDrafts.rows(shown.solution()));
        model.addAttribute("topicPaths", taxonomy.topicPaths());
        model.addAttribute("topicMethods",
                topic == null ? List.of() : problems.methodsUsedIn(new TaxonomyNodeId(topic)));
        model.addAttribute("methods", methods.all());
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("parts", ExamPart.values());
        return "problem/intake";
    }

    private LocalDateTime local(ProblemDraft draft) {
        return LocalDateTime.ofInstant(draft.changedAt(), clock.getZone());
    }

    /** Чем черновик назван в списке: первая непустая строка условия без пометок. */
    private static String title(ProblemDraftContent content) {
        String first = DoubtMarks.strip(content.conditionText()).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        return first.length() > TITLE_LENGTH ? first.substring(0, TITLE_LENGTH) + "…" : first;
    }

    /** Строка списка черновиков. */
    public record Listed(ProblemDraft draft, LocalDateTime changedAt, String title) {
    }

    /**
     * Присланное формой вычитки. Страница шлёт все поля всегда, поэтому
     * изменение заменяет все части, кроме тех, которых на ней не правят:
     * причин сомнений и новых Методов — их называет Помощник.
     */
    private record Sent(ExamPart part, List<Long> topics, List<Long> methods, List<Long> characteristics,
                        String conditionText, String solutionText,
                        List<Long> conditionDraft, List<Integer> conditionFrom, List<Integer> conditionTo,
                        List<String> conditionCrop,
                        List<Long> solutionDraft, List<Integer> solutionFrom, List<Integer> solutionTo,
                        List<String> solutionCrop) {

        ProblemDraftChange change() {
            return new ProblemDraftChange(
                    Optional.ofNullable(part),
                    Objects.requireNonNullElse(conditionText, ""),
                    Objects.requireNonNullElse(solutionText, ""),
                    AssemblyOrderForm.order(conditionDraft, conditionFrom, conditionTo, conditionCrop),
                    AssemblyOrderForm.order(solutionDraft, solutionFrom, solutionTo, solutionCrop),
                    null,
                    ids(topics, TaxonomyNodeId::new),
                    ids(methods, SolutionMethodId::new),
                    ids(characteristics, CharacteristicId::new),
                    null);
        }

        private static <T> List<T> ids(List<Long> values, java.util.function.LongFunction<T> id) {
            List<T> ids = new ArrayList<>();
            if (values != null) {
                values.stream().filter(Objects::nonNull).forEach(value -> ids.add(id.apply(value)));
            }
            return ids;
        }
    }
}
