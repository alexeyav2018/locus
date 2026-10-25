package ru.locus.problem;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import ru.locus.Addresses;
import ru.locus.ReturnTo;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethod;
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
 * <p>Форма одна, закладки — панели в ней (intake-tabs, Р1); кнопок четыре
 * ({@code formaction}): «Сохранить черновик», «Сохранить как Задачу»,
 * «Сохранить снимком, без текста» и «Обновить отрисовку». Все шлют
 * введённое и прочитанную версию, «Обновить отрисовку» ничего не пишет (Р3). Утверждение — два вызова сервиса: сначала запись
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
    public String list(@RequestParam(required = false) Long created, Model model) {
        if (created != null) {
            created(new ProblemId(created)).ifPresent(problem -> model.addAttribute("created", problem));
        }
        model.addAttribute("drafts", intake.drafts().stream()
                .map(draft -> new Listed(draft, local(draft), title(draft.content())))
                .toList());
        return "problem/intake-list";
    }

    /** Страница вычитки: Улика рядом с отрисовкой, поля правки, разметка. */
    @GetMapping(Addresses.PROBLEM_INTAKE + "/{id}")
    public String draft(@PathVariable long id, @RequestParam(required = false) String tab, Model model) {
        ProblemDraft draft = intake.draft(new ProblemDraftId(id));
        return page(draft, draft.content(), Tab.of(tab), model);
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
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String tab,
                        Model model) {
        Sent sent = new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                conditionDraft, conditionFrom, conditionTo, conditionCrop,
                solutionDraft, solutionFrom, solutionTo, solutionCrop);
        ProblemDraftId draftId = new ProblemDraftId(id);
        Tab open = Tab.of(tab);
        try {
            written(draftId, version, sent);
            return "redirect:" + ReturnTo.carried(Addresses.PROBLEM_INTAKE + "/" + id + "?tab=" + open.key(), from);
        } catch (ProblemDraftChangedException refusal) {
            return refusedStored(draftId, refusal, open, model);
        } catch (NotATopicException | IllegalArgumentException refusal) {
            return refusedSent(draftId, sent, refusal, open, model);
        }
    }

    /**
     * «Обновить отрисовку» (intake-tabs, design.md, Р3): страница от присланного,
     * черновик не пишется. Версия — присланная: правка Помощника, сделанная
     * тем временем, остаётся поводом для «перечитайте» при сохранении.
     * В полях не хранимое — страница несохранена сразу.
     */
    @PostMapping(Addresses.PROBLEM_INTAKE + "/{id}/preview")
    public String preview(@PathVariable long id,
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
                          @RequestParam(required = false) String tab,
                          Model model) {
        Sent sent = new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                conditionDraft, conditionFrom, conditionTo, conditionCrop,
                solutionDraft, solutionFrom, solutionTo, solutionCrop);
        ProblemDraftId draftId = new ProblemDraftId(id);
        Tab open = Tab.of(tab);
        ProblemDraft stored = intake.draft(draftId);
        String page = page(stored, sent.change().applyTo(stored.content()), version, open, model);
        try {
            AssemblyOrderForm.requireFrames(sent.conditionCrop());
            AssemblyOrderForm.requireFrames(sent.solutionCrop());
        } catch (IllegalArgumentException refusal) {
            AssemblyRefusals.report(refusal);
            model.addAttribute("error", refusal.getMessage());
        }
        model.addAttribute("sent", true);
        return page;
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
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String tab,
                                Model model) {
        return saved(new ProblemDraftId(id), version, ProblemDraftService.Kind.TEXT,
                new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                        conditionDraft, conditionFrom, conditionTo, conditionCrop,
                        solutionDraft, solutionFrom, solutionTo, solutionCrop),
                from, Tab.of(tab), model);
    }

    /** «Сохранить снимком, без текста»: Задача без текста, PDF слотов — из строк сборки. */
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
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String tab,
                               Model model) {
        return saved(new ProblemDraftId(id), version, ProblemDraftService.Kind.SNAPSHOT,
                new Sent(part, topics, methodIds, characteristicIds, conditionText, solutionText,
                        conditionDraft, conditionFrom, conditionTo, conditionCrop,
                        solutionDraft, solutionFrom, solutionTo, solutionCrop),
                from, Tab.of(tab), model);
    }

    /** «Удалить черновик»: Задачи не заводит, ведёт к оставшимся черновикам (back-link-gaps, Р4). */
    @PostMapping(Addresses.PROBLEM_INTAKE + "/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam long version,
                         @RequestParam(required = false) String from, Model model) {
        ProblemDraftId draftId = new ProblemDraftId(id);
        try {
            intake.delete(draftId, version);
            return away(from, null);
        } catch (ProblemDraftChangedException refusal) {
            return refusedStored(draftId, refusal, Tab.EVIDENCE, model);
        }
    }

    /**
     * Утверждение: сначала запись введённого своим вызовом, затем заведение
     * Задачи с выросшей версией (Р4). Отказ записи — как у «Сохранить
     * черновик»; отказ заведения — хранимый черновик с отказом: введённое
     * уже записано.
     */
    private String saved(ProblemDraftId id, long version, ProblemDraftService.Kind kind, Sent sent,
                         String from, Tab tab, Model model) {
        ProblemDraft written;
        try {
            written = written(id, version, sent);
        } catch (ProblemDraftChangedException refusal) {
            return refusedStored(id, refusal, tab, model);
        } catch (NotATopicException | IllegalArgumentException refusal) {
            return refusedSent(id, sent, refusal, tab, model);
        }
        try {
            ProblemId created = intake.save(id, written.version(), kind);
            return away(from, created);
        } catch (ProblemDraftChangedException | NotATopicException | IllegalArgumentException refusal) {
            return refusedStored(id, refusal, tab, model);
        }
    }

    /**
     * Уход со страницы черновика после утверждения или удаления
     * (back-link-gaps, design.md, Р4): к оставшимся черновикам — списком
     * с возвратом страницы черновика, а без оставшихся — туда, куда вела бы
     * ссылка «назад» списка. Открытая из списка страница несёт возвратом
     * сам список; тогда возврат списка — его собственный {@code from},
     * и список строится заново с ним, без вложения списка в список
     * (прежний {@code created} при этом заменяется).
     *
     * @param created заведённая Задача — список назовёт её; {@code null} у удаления
     */
    private String away(String from, ProblemId created) {
        String back = ReturnTo.safe(from).orElse(null);
        String listBack = back != null && pathOf(back).equals(Addresses.PROBLEM_INTAKE) ? fromOf(back) : back;
        if (intake.count() == 0) {
            return "redirect:" + ReturnTo.safe(listBack).map(ReturnTo::location).orElse(Addresses.ASSISTANT);
        }
        String list = created == null
                ? Addresses.PROBLEM_INTAKE
                : Addresses.PROBLEM_INTAKE + "?created=" + created.value();
        return "redirect:" + ReturnTo.carried(list, listBack);
    }

    private static String pathOf(String address) {
        int end = address.length();
        for (char stop : new char[] {'?', '#'}) {
            int at = address.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return address.substring(0, end);
    }

    /** Собственный возврат адреса: значение его параметра {@code from}, раскодированное. */
    private static String fromOf(String address) {
        String value = UriComponentsBuilder.fromUriString(address).build().getQueryParams().getFirst("from");
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Заведённая Задача для надписи списка (Р5). Номер приходит из адреса:
     * нет такой Задачи — нет и надписи, отказа нет — это подсказка, а не данные.
     */
    private Optional<Problem> created(ProblemId id) {
        try {
            return Optional.of(problems.problem(id));
        } catch (IllegalArgumentException missing) {
            return Optional.empty();
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
    private String refusedStored(ProblemDraftId id, RuntimeException refusal, Tab tab, Model model) {
        AssemblyRefusals.report(refusal);
        ProblemDraft stored = intake.draft(id);
        String page = page(stored, stored.content(), tab, model);
        model.addAttribute("error", refusal.getMessage());
        return page;
    }

    /**
     * Отказ записи разметкой или строкой: черновик прежний, а в полях —
     * присланное, чтобы человек не набирал его заново. Версия — хранимая:
     * она и есть прочитанная.
     */
    private String refusedSent(ProblemDraftId id, Sent sent, RuntimeException refusal, Tab tab, Model model) {
        AssemblyRefusals.report(refusal);
        ProblemDraft stored = intake.draft(id);
        String page = page(stored, sent.change().applyTo(stored.content()), stored.version(), tab, model);
        model.addAttribute("error", refusal.getMessage());
        // В полях не хранимое: уход со страницы теряет его и без новой правки
        // (intake-unsaved-warning, design.md, решение 2).
        model.addAttribute("sent", true);
        return page;
    }

    private String page(ProblemDraft stored, ProblemDraftContent shown, Tab tab, Model model) {
        return page(stored, shown, stored.version(), tab, model);
    }

    /**
     * Модель страницы вычитки. Показывается {@code shown} — хранимое
     * или присланное до отказа либо для отрисовки; отметка правки —
     * хранимого, версия — {@code version}: хранимая либо прочитанная
     * страницей, если показанное от неё (Р3). Открыта закладка {@code tab}.
     */
    private String page(ProblemDraft stored, ProblemDraftContent shown, long version, Tab tab, Model model) {
        ProblemDraft draft = new ProblemDraft(stored.id(), stored.owner(), shown, version,
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
        List<SolutionMethod> dictionary = methods.all();
        model.addAttribute("methods", dictionary);
        model.addAttribute("newMethods", unknown(shown.newMethods(), dictionary));
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("parts", ExamPart.values());
        model.addAttribute("tab", tab.key());
        return "problem/intake";
    }

    private LocalDateTime local(ProblemDraft draft) {
        return LocalDateTime.ofInstant(draft.changedAt(), clock.getZone());
    }

    /**
     * Новые Методы Помощника, которых в словаре ещё нет (Р5): заведённый
     * Метод из показа уходит. Сравнение — как уникальность словаря, без
     * учёта регистра и крайних пробелов. Хранимый список не меняется.
     */
    private static List<String> unknown(List<String> named, List<SolutionMethod> dictionary) {
        Set<String> known = new HashSet<>();
        dictionary.forEach(method -> known.add(comparable(method.name())));
        return named.stream().filter(name -> !known.contains(comparable(name))).toList();
    }

    private static String comparable(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Чем черновик назван в списке: первая непустая строка условия без пометок
     * и без записи формул — LaTeX в строке таблицы не читается (intake-tabs, Р8).
     */
    private static String title(ProblemDraftContent content) {
        String first = ProblemTextRendering.withoutFormulas(DoubtMarks.strip(content.conditionText())).lines()
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
     * Закладка страницы вычитки (Р2): её открывает адрес, её же присылает
     * форма. Неизвестное и отсутствующее — Улика: с неё вычитка начинается.
     */
    enum Tab {
        EVIDENCE("evidence"), CONDITION("condition"), SOLUTION("solution"), MARKUP("markup");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        static Tab of(String key) {
            for (Tab tab : values()) {
                if (tab.key.equals(key)) {
                    return tab;
                }
            }
            return EVIDENCE;
        }
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
