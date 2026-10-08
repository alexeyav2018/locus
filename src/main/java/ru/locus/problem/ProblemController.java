package ru.locus.problem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import ru.locus.Addresses;
import ru.locus.file.FileView;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;

/**
 * Страницы Задачи: заведение, правка и просмотр.
 *
 * Список Задач Темы живёт не здесь, а в правой части экрана дерева
 * ({@code taxonomy/tree.html}): «выбрать Тему» — единственная навигация,
 * которая у библиотеки сегодня есть, и второе дерево на отдельном экране
 * разъехалось бы с первым сначала оформлением, потом поведением
 * (design.md, «Экран»).
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяет {@link ProblemService} на каждой операции,
 * поэтому отказ происходит одинаково и при нажатии кнопки, и при обращении
 * по прямому адресу.
 *
 * Роль здесь читается ровно для одного — показывать ли формы правки.
 * Правами это не является: разметка ничего не разрешает и не запрещает.
 */
@Controller
public class ProblemController {

    private final ProblemService problems;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;
    private final CurrentUser currentUser;
    private final AssemblyDraftService drafts;

    public ProblemController(ProblemService problems,
                             TaxonomyService taxonomy,
                             SolutionMethodService methods,
                             CharacteristicService characteristics,
                             CurrentUser currentUser,
                             AssemblyDraftService drafts) {
        this.problems = problems;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
        this.currentUser = currentUser;
        this.drafts = drafts;
    }

    /**
     * Форма заведения. Тема, с которой пришли, выбрана заранее: заводят Задачу
     * с экрана дерева, стоя на Теме, и заставлять выбирать её второй раз —
     * приглашение ошибиться.
     *
     * Показ формы — открытие инструмента сборки, и с него начинается уборка
     * брошенных черновиков (ADR-0044).
     */
    @GetMapping(Addresses.PROBLEMS + "/new")
    public String form(@RequestParam(required = false) Long topic, Model model) {
        drafts.sweep();
        fillMarkup(ProblemForm.startingAt(topic), model);
        return "problem/form";
    }

    @PostMapping(Addresses.PROBLEMS)
    public String create(@RequestParam(required = false) String caption,
                         @RequestParam(required = false) ExamPart part,
                         @RequestParam(required = false) List<Long> topics,
                         @RequestParam(required = false) List<Long> methodIds,
                         @RequestParam(required = false) List<Long> characteristicIds,
                         @RequestParam(required = false) MultipartFile condition,
                         @RequestParam(required = false) MultipartFile solution,
                         @RequestParam(required = false) List<Long> conditionDraft,
                         @RequestParam(required = false) List<Integer> conditionFrom,
                         @RequestParam(required = false) List<Integer> conditionTo,
                         @RequestParam(required = false) List<String> conditionCrop,
                         @RequestParam(required = false) List<Long> solutionDraft,
                         @RequestParam(required = false) List<Integer> solutionFrom,
                         @RequestParam(required = false) List<Integer> solutionTo,
                         @RequestParam(required = false) List<String> solutionCrop,
                         Model model) {
        PdfAssemblyOrder conditionOrder = order(conditionDraft, conditionFrom, conditionTo, conditionCrop);
        PdfAssemblyOrder solutionOrder = order(solutionDraft, solutionFrom, solutionTo, solutionCrop);
        try {
            requireFrames(conditionCrop);
            requireFrames(solutionCrop);
            ProblemId created = problems.create(caption, part,
                    nodeIds(topics), methodIds(methodIds), characteristicIds(characteristicIds),
                    slot(condition, conditionOrder, "условия"), slot(solution, solutionOrder, "решения"));
            return "redirect:" + Addresses.PROBLEMS + "/" + created.value();
        } catch (NotATopicException | IllegalArgumentException refusal) {
            fillMarkup(ProblemForm.sent(caption, part, topics, methodIds, characteristicIds), model);
            model.addAttribute("conditionRows", drafts.rows(conditionOrder));
            model.addAttribute("solutionRows", drafts.rows(solutionOrder));
            model.addAttribute("error", refusal.getMessage());
            return "problem/form";
        }
    }

    /** Просмотр Задачи: разметка, номер, подпись и обе временные ссылки. */
    @GetMapping(Addresses.PROBLEMS + "/{id}")
    public String problem(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("problem", problem);
        model.addAttribute("topicNames", taxonomy.paths().stream()
                .filter(path -> problem.topics().contains(path.id()))
                .map(path -> path.path())
                .toList());
        model.addAttribute("methodNames", methods.all().stream()
                .filter(method -> problem.methods().contains(method.id()))
                .map(method -> method.name())
                .toList());
        model.addAttribute("characteristicNames", characteristics.all().stream()
                .filter(characteristic -> problem.characteristics().contains(characteristic.id()))
                .map(characteristic -> characteristic.name())
                .toList());
        model.addAttribute("conditionLink", problems.conditionLink(problem.id()).toString());
        model.addAttribute("solutionLink", problems.solutionLink(problem.id()).toString());
        model.addAttribute("administrator", currentUser.account().hasRole(Role.ADMINISTRATOR));
        return "problem/problem";
    }

    /** Просмотр PDF условия внутри системы: страница с кнопкой «Назад» вместо чужой вкладки. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/condition")
    public String viewCondition(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("view", FileView.of("Условие — Задача № " + problem.number(),
                problems.conditionLink(problem.id()).toString(), problem.conditionFile(),
                Addresses.PROBLEMS + "/" + id));
        return "file/viewer";
    }

    /** Просмотр PDF решения внутри системы. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/solution")
    public String viewSolution(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("view", FileView.of("Решение — Задача № " + problem.number(),
                problems.solutionLink(problem.id()).toString(), problem.solutionFile(),
                Addresses.PROBLEMS + "/" + id));
        return "file/viewer";
    }

    /** Форма правки — та же, что и заведения, но с заполненной Задачей. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        drafts.sweep();
        Problem problem = problems.problem(new ProblemId(id));
        return editForm(problem, ProblemForm.of(problem), model);
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}")
    public String edit(@PathVariable long id,
                       @RequestParam(required = false) String caption,
                       @RequestParam(required = false) ExamPart part,
                       @RequestParam(required = false) List<Long> topics,
                       @RequestParam(required = false) List<Long> methodIds,
                       @RequestParam(required = false) List<Long> characteristicIds,
                       Model model) {
        try {
            problems.edit(new ProblemId(id), caption, part,
                    nodeIds(topics), methodIds(methodIds), characteristicIds(characteristicIds));
        } catch (ProblemInUseException | NotATopicException | IllegalArgumentException refusal) {
            Problem problem = problems.problem(new ProblemId(id));
            String page = editForm(problem, ProblemForm.sent(caption, part, topics, methodIds, characteristicIds),
                    model);
            model.addAttribute("error", refusal.getMessage());
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}/condition")
    public String replaceCondition(@PathVariable long id,
                                   @RequestParam(required = false) MultipartFile condition,
                                   @RequestParam(required = false) List<Long> conditionDraft,
                                   @RequestParam(required = false) List<Integer> conditionFrom,
                                   @RequestParam(required = false) List<Integer> conditionTo,
                                   @RequestParam(required = false) List<String> conditionCrop,
                                   Model model) {
        PdfAssemblyOrder order = order(conditionDraft, conditionFrom, conditionTo, conditionCrop);
        try {
            requireFrames(conditionCrop);
            problems.replaceCondition(new ProblemId(id), slot(condition, order, "условия"));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            String page = refusedEdit(id, refusal, model);
            model.addAttribute("conditionRows", drafts.rows(order));
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}/solution")
    public String replaceSolution(@PathVariable long id,
                                  @RequestParam(required = false) MultipartFile solution,
                                  @RequestParam(required = false) List<Long> solutionDraft,
                                  @RequestParam(required = false) List<Integer> solutionFrom,
                                  @RequestParam(required = false) List<Integer> solutionTo,
                                  @RequestParam(required = false) List<String> solutionCrop,
                                  Model model) {
        PdfAssemblyOrder order = order(solutionDraft, solutionFrom, solutionTo, solutionCrop);
        try {
            requireFrames(solutionCrop);
            problems.replaceSolution(new ProblemId(id), slot(solution, order, "решения"));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            String page = refusedEdit(id, refusal, model);
            model.addAttribute("solutionRows", drafts.rows(order));
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    /**
     * Снимает Задачу и возвращает на Тему, где она лежала: самой Задачи больше
     * нет, а место, где работал Администратор, теряться не должно.
     */
    @PostMapping(Addresses.PROBLEMS + "/{id}/deletion")
    public String delete(@PathVariable long id, Model model) {
        try {
            Problem problem = problems.problem(new ProblemId(id));
            long topic = problem.topics().get(0).value();
            problems.delete(problem.id());
            return "redirect:" + Addresses.TAXONOMY + "?node=" + topic + "#node-" + topic;
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
    }

    private String editForm(Problem problem, ProblemForm form, Model model) {
        model.addAttribute("problem", problem);
        fillMarkup(form, model);
        return "problem/form";
    }

    /**
     * Что отмечено в форме разметки и что в ней предлагается: Темы дерева,
     * Методы и Характеристики.
     *
     * Методы Темы идут отдельным списком и показываются первыми, полный
     * словарь — вторым: на сотне записей выбор из полного списка превращается
     * в перебор, при котором заводится смысловой дубль вместо существующей
     * записи (ADR-0009, ADR-0010). Выбор при этом ничем не ограничен — Метод,
     * в Теме не встречавшийся, указать можно. Подсказка строится по первой
     * отмеченной Теме — после отказа это первая присланная.
     */
    private void fillMarkup(ProblemForm form, Model model) {
        Long topic = form.firstTopic();
        model.addAttribute("form", form);
        model.addAttribute("topicPaths", taxonomy.topicPaths());
        model.addAttribute("topicMethods",
                topic == null ? List.of() : problems.methodsUsedIn(new TaxonomyNodeId(topic)));
        model.addAttribute("methods", methods.all());
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("parts", ExamPart.values());
    }

    /**
     * Отказ замены файла или удаления: форма с сохранённой Задачей. Разметку
     * эти формы не присылают, поэтому показывать, кроме сохранённой, нечего.
     */
    private String refusedEdit(long id, RuntimeException refusal, Model model) {
        String message = refusal.getMessage();
        String page = edit(id, model);
        model.addAttribute("error", message);
        return page;
    }

    /**
     * Присланный файл к виду, понятному сервису. Пустое поле — это отсутствие
     * файла, а не пустой файл: отказ о недостающем PDF должен звучать
     * одинаково и когда поле не заполнено, и когда его вовсе нет в запросе.
     */
    private static UploadedFile uploaded(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new UploadedFile(file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Слот из формы: готовый файл или порядок сборки, но не оба сразу.
     *
     * Выбор одного из двух — разбор формы, а не правило Задачи: сервис
     * получает ровно один вариант, и что он был единственным, решается здесь
     * (design.md, «Слот принимает „готовый файл или порядок сборки“»).
     */
    private static ProblemPdf slot(MultipartFile file, PdfAssemblyOrder order, String what) {
        UploadedFile ready = uploaded(file);
        if (ready != null && !order.isEmpty()) {
            throw new IllegalArgumentException("PDF " + what
                    + ": приложен готовый файл и задана сборка — выберите один способ");
        }
        return ready != null ? ready : order.isEmpty() ? null : order;
    }

    /**
     * Порядок сборки из повторяющихся полей строк: «черновик», «с», «по»,
     * «рамка» идут в строках формы по одному, и i-е значения каждого списка —
     * одна строка. Незаполненная страница — нуль: такой диапазон отклонит
     * сборка, назвав источник и число его страниц, а не разбор формы безлико.
     *
     * <p>Рамка здесь разбирается снисходительно: испорченная считается
     * отсутствующей, чтобы порядок можно было перерисовать в форме после
     * отказа. Сам отказ испорченной рамке даёт {@link #requireFrames} внутри
     * обработки — иначе вместо формы с текстом была бы страница ошибки.
     */
    private static PdfAssemblyOrder order(List<Long> draftIds, List<Integer> from, List<Integer> to,
                                          List<String> crops) {
        if (draftIds == null) {
            return new PdfAssemblyOrder(List.of());
        }
        List<PdfAssemblyOrder.Line> lines = new ArrayList<>();
        for (int i = 0; i < draftIds.size(); i++) {
            if (draftIds.get(i) == null) {
                continue;
            }
            lines.add(new PdfAssemblyOrder.Line(new AssemblyDraftId(draftIds.get(i)), page(from, i), page(to, i),
                    frameOrNull(crops, i)));
        }
        return new PdfAssemblyOrder(lines);
    }

    private static CropFrame frameOrNull(List<String> crops, int i) {
        if (crops == null || i >= crops.size()) {
            return null;
        }
        try {
            return CropFrame.parse(crops.get(i));
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    /** Отказ формы, если хоть одна присланная рамка испорчена или неверна. */
    private static void requireFrames(List<String> crops) {
        if (crops != null) {
            crops.forEach(CropFrame::parse);
        }
    }

    private static int page(List<Integer> pages, int i) {
        return pages == null || i >= pages.size() || pages.get(i) == null ? 0 : pages.get(i);
    }

    private static List<TaxonomyNodeId> nodeIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(TaxonomyNodeId::new).toList();
    }

    private static List<SolutionMethodId> methodIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(SolutionMethodId::new).toList();
    }

    private static List<CharacteristicId> characteristicIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(CharacteristicId::new).toList();
    }
}
