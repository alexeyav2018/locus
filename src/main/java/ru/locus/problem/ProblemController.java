package ru.locus.problem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import ru.locus.Addresses;
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

    public ProblemController(ProblemService problems,
                             TaxonomyService taxonomy,
                             SolutionMethodService methods,
                             CharacteristicService characteristics,
                             CurrentUser currentUser) {
        this.problems = problems;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
        this.currentUser = currentUser;
    }

    /**
     * Форма заведения. Тема, с которой пришли, выбрана заранее: заводят Задачу
     * с экрана дерева, стоя на Теме, и заставлять выбирать её второй раз —
     * приглашение ошибиться.
     */
    @GetMapping(Addresses.PROBLEMS + "/new")
    public String form(@RequestParam(required = false) Long topic, Model model) {
        model.addAttribute("chosenTopic", topic == null ? 0L : topic);
        fillMarkupChoices(topic, model);
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
                         Model model) {
        try {
            ProblemId created = problems.create(caption, part,
                    nodeIds(topics), methodIds(methodIds), characteristicIds(characteristicIds),
                    uploaded(condition), uploaded(solution));
            return "redirect:" + Addresses.PROBLEMS + "/" + created.value();
        } catch (NotATopicException | IllegalArgumentException refusal) {
            model.addAttribute("chosenTopic", first(topics));
            fillMarkupChoices(first(topics) == 0 ? null : first(topics), model);
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

    /** Форма правки — та же, что и заведения, но с заполненной Задачей. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("problem", problem);
        model.addAttribute("chosenTopic", problem.topics().isEmpty() ? 0L : problem.topics().get(0).value());
        fillMarkupChoices(problem.topics().isEmpty() ? null : problem.topics().get(0).value(), model);
        return "problem/form";
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
            return refusedEdit(id, refusal, model);
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}/condition")
    public String replaceCondition(@PathVariable long id,
                                   @RequestParam(required = false) MultipartFile condition,
                                   Model model) {
        try {
            problems.replaceCondition(new ProblemId(id), uploaded(condition));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}/solution")
    public String replaceSolution(@PathVariable long id,
                                  @RequestParam(required = false) MultipartFile solution,
                                  Model model) {
        try {
            problems.replaceSolution(new ProblemId(id), uploaded(solution));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
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

    /**
     * Что предлагается в форме разметки: Темы дерева, Методы и Характеристики.
     *
     * Методы Темы идут отдельным списком и показываются первыми, полный
     * словарь — вторым: на сотне записей выбор из полного списка превращается
     * в перебор, при котором заводится смысловой дубль вместо существующей
     * записи (ADR-0009, ADR-0010). Выбор при этом ничем не ограничен — Метод,
     * в Теме не встречавшийся, указать можно.
     */
    private void fillMarkupChoices(Long topic, Model model) {
        model.addAttribute("topicPaths", taxonomy.topicPaths());
        model.addAttribute("topicMethods",
                topic == null ? List.of() : problems.methodsUsedIn(new TaxonomyNodeId(topic)));
        model.addAttribute("methods", methods.all());
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("parts", ExamPart.values());
    }

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

    private static List<TaxonomyNodeId> nodeIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(TaxonomyNodeId::new).toList();
    }

    private static List<SolutionMethodId> methodIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(SolutionMethodId::new).toList();
    }

    private static List<CharacteristicId> characteristicIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(CharacteristicId::new).toList();
    }

    /** «Тема не выбрана» — нуль: идентификаторы положительны по построению. */
    private static long first(List<Long> topics) {
        return topics == null || topics.isEmpty() ? 0L : topics.get(0);
    }
}
