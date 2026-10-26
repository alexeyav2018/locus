package ru.locus.warmup;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.ReturnTo;
import ru.locus.assignment.Addressee;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryService;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.student.GroupService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;

/**
 * Экраны Разминок: список с отбором по Ученику, форма заведения из поиска,
 * страница Разминки с итогами и отметками участников и удаление.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяют {@link WarmupService} и {@link MasteryService}
 * на каждой операции, поэтому отказ одинаков и при нажатии кнопки, и по
 * прямому адресу.
 *
 * Владельца контроллер <b>не знает</b>: ни из пути, ни из параметра, ни
 * из {@code CurrentUser}. Разминки — личный контур, владельца подставляет
 * сервис (ADR-0027); чужая Разминка неотличима от несуществующей —
 * {@link WarmupNotFoundException} не перехватывается и отвечает 404.
 *
 * Адресат — ровно один, Ученик либо Группа: правило живёт
 * в {@link Addressee#of}, как у выдачи Задания. Задачи в форму приходят
 * из поиска по библиотеке параметром {@code problem} — столько раз, сколько
 * отмечено.
 *
 * Возврат {@code from} переживает действия страницы (ADR-0060): заведение
 * ведёт на страницу Разминки, сохранение итогов — на неё же, удаление —
 * на список, все с тем же возвратом.
 *
 * Итоги и отметки участника приходят параллельными списками
 * {@code problem}/{@code outcome} и {@code topic}/{@code method}/{@code status};
 * читаются они сырыми из запроса — почему, сказано в {@link Mark#fromForm}.
 * Отклонённая форма показывается снова с введённым ({@link EnteredOutcomes}).
 */
@Controller
public class WarmupController {

    private final WarmupService warmups;
    private final MasteryService mastery;
    private final ProblemService problems;
    private final StudentService students;
    private final GroupService groups;

    public WarmupController(WarmupService warmups,
                            MasteryService mastery,
                            ProblemService problems,
                            StudentService students,
                            GroupService groups) {
        this.warmups = warmups;
        this.mastery = mastery;
        this.problems = problems;
        this.students = students;
        this.groups = groups;
    }

    /**
     * Список, новые по дате первыми. С {@code student} — Разминки, где он
     * участник; чужой Ученик — 404 из сервиса.
     */
    @GetMapping(Addresses.WARMUPS)
    public String list(@RequestParam(required = false) Long student, Model model) {
        StudentId studentId = student == null ? null : new StudentId(student);
        model.addAttribute("warmups", warmups.list(studentId));
        model.addAttribute("student", studentId == null ? null : students.student(studentId));
        return "warmup/list";
    }

    /**
     * Форма заведения с Задачами, отмеченными в поиске, и сегодняшней датой.
     * Несуществующий номер в адресе — форма без Задач с причиной в сообщении.
     */
    @GetMapping(Addresses.WARMUPS + "/new")
    public String form(@RequestParam(name = "problem", required = false) List<Long> problemIds, Model model) {
        if (!model.containsAttribute("heldOn")) {
            model.addAttribute("heldOn", warmups.today());
        }
        try {
            return renderForm(problemIds, model);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderForm(List.of(), model);
        }
    }

    @PostMapping(Addresses.WARMUPS)
    public String hold(@RequestParam(name = "problem", required = false) List<Long> problemIds,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate heldOn,
                       @RequestParam(required = false) Long student,
                       @RequestParam(required = false) Long group,
                       @RequestParam(required = false) String from,
                       Model model) {
        List<ProblemId> ids = problemIds == null ? List.of() : problemIds.stream().map(ProblemId::new).toList();
        try {
            WarmupId id = warmups.hold(Addressee.of(student, group), heldOn, ids);
            return "redirect:" + ReturnTo.carried(Addresses.WARMUPS + "/" + id.value(), from);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("heldOn", heldOn);
            model.addAttribute("chosenStudent", student);
            model.addAttribute("chosenGroup", group);
            return form(problemIds, model);
        }
    }

    @GetMapping(Addresses.WARMUPS + "/{id}")
    public String warmup(@PathVariable long id, Model model) {
        return renderWarmup(new WarmupId(id), model);
    }

    /**
     * Итоги и отметки Владения одного участника — одним сохранением.
     * Отказ — страница с сообщением и раскрытым блоком этого участника
     * с введённым; сервис откатывает всё.
     */
    @PostMapping(Addresses.WARMUPS + "/{id}/students/{studentId}")
    public String record(@PathVariable long id,
                         @PathVariable long studentId,
                         @RequestParam(required = false) String from,
                         HttpServletRequest request,
                         Model model) {
        WarmupId warmupId = new WarmupId(id);
        StudentId student = new StudentId(studentId);
        List<Outcome> outcomes = List.of();
        List<Mark> marks = List.of();
        try {
            outcomes = Outcome.fromForm(request.getParameterValues("problem"), request.getParameterValues("outcome"));
            marks = Mark.fromForm(request.getParameterValues("topic"),
                                  request.getParameterValues("method"),
                                  request.getParameterValues("status"));
            warmups.record(warmupId, student, outcomes, marks);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("entered", EnteredOutcomes.of(student, outcomes, marks));
            return renderWarmup(warmupId, model);
        }
        return "redirect:" + ReturnTo.carried(
                Addresses.WARMUPS + "/" + id + "#student-" + studentId, from);
    }

    /** Удаляет Разминку и ведёт к списку: страницы больше нет. */
    @PostMapping(Addresses.WARMUPS + "/{id}/deletion")
    public String delete(@PathVariable long id, @RequestParam(required = false) String from) {
        warmups.delete(new WarmupId(id));
        return "redirect:" + ReturnTo.carried(Addresses.WARMUPS, from);
    }

    private String renderForm(List<Long> problemIds, Model model) {
        List<ProblemId> ids = problemIds == null ? List.of() : problemIds.stream().map(ProblemId::new).toList();
        model.addAttribute("problems", problems.problems(ids));
        model.addAttribute("students", students.active());
        model.addAttribute("groups", groups.all());
        return "warmup/form";
    }

    private String renderWarmup(WarmupId id, Model model) {
        ListedWarmup listed = warmups.warmup(id);
        model.addAttribute("listed", listed);
        model.addAttribute("problems", warmups.problemsOf(listed.warmup()));
        model.addAttribute("outcomes", warmups.outcomesOf(listed.warmup()));
        model.addAttribute("outcomeValues", WarmupOutcome.values());
        model.addAttribute("cells", mastery.cellsOfWarmup(id));
        model.addAttribute("statuses", MasteryStatus.values());
        return "warmup/warmup";
    }
}
