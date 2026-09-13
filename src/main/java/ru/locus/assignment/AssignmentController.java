package ru.locus.assignment;

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
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.student.GroupService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;

/**
 * Экраны Заданий: сводка с отбором, форма выдачи, страница Задания
 * с переносом срока и удалением, страница Раздачи с удалением.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяет {@link AssignmentService} на каждой
 * операции, поэтому отказ одинаков и при нажатии кнопки, и по прямому
 * адресу.
 *
 * Владельца контроллер <b>не знает</b>: ни из пути, ни из параметра, ни
 * из {@code CurrentUser}. Задания — личный контур, владельца подставляет
 * сервис (ADR-0027); чужое Задание и чужая Раздача неотличимы
 * от несуществующих — {@link AssignmentNotFoundException}
 * и {@link AssignmentBatchNotFoundException} не перехватываются
 * и отвечают 404 в обоих случаях.
 *
 * Адресат выдачи — ровно один, Ученик либо Группа: правило живёт
 * в {@link Addressee#of}, контроллер лишь зовёт фабрику и раскладывает
 * по двум операциям сервиса (design.md, «Выдача»). Задачи в форму приходят
 * из поиска по библиотеке параметром {@code problem} — столько раз, сколько
 * отмечено.
 *
 * Даты из форм — {@code <input type="date">}, то есть ISO
 * {@code yyyy-MM-dd}; формат назван на параметре явно, чтобы разбор
 * не зависел от локали сервера.
 */
@Controller
public class AssignmentController {

    private final AssignmentService assignments;
    private final ProblemService problems;
    private final StudentService students;
    private final GroupService groups;

    public AssignmentController(AssignmentService assignments,
                                ProblemService problems,
                                StudentService students,
                                GroupService groups) {
        this.assignments = assignments;
        this.problems = problems;
        this.students = students;
        this.groups = groups;
    }

    /**
     * Сводка. Условия отбора в адресе, форма {@code GET} — как у поиска
     * по библиотеке: у отобранного есть собственная ссылка.
     */
    @GetMapping(Addresses.ASSIGNMENTS)
    public String list(@RequestParam(required = false) Long student,
                       @RequestParam(required = false) Long batch,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "false") boolean notSubmitted,
                       Model model) {
        model.addAttribute("students", students.all());
        model.addAttribute("batches", assignments.batches());
        try {
            AssignmentFilter filter = new AssignmentFilter(
                    student == null ? null : new StudentId(student),
                    batch == null ? null : new AssignmentBatchId(batch),
                    from, to, notSubmitted);
            model.addAttribute("filter", filter);
            model.addAttribute("assignments", assignments.list(filter));
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("filter", AssignmentFilter.none());
            model.addAttribute("assignments", List.of());
            model.addAttribute("error", refusal.getMessage());
        }
        return "assignment/list";
    }

    /**
     * Форма выдачи с Задачами, отмеченными в поиске. Несуществующий номер
     * в адресе — форма без Задач с причиной в сообщении; сюда же
     * возвращается и отклонённая выдача, чтобы показать форму заново.
     */
    @GetMapping(Addresses.ASSIGNMENTS + "/new")
    public String form(@RequestParam(name = "problem", required = false) List<Long> problemIds, Model model) {
        try {
            return renderForm(problemIds, model);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderForm(List.of(), model);
        }
    }

    @PostMapping(Addresses.ASSIGNMENTS)
    public String issue(@RequestParam(name = "problem", required = false) List<Long> problemIds,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                        @RequestParam(required = false) TheoryScope theoryScope,
                        @RequestParam(required = false) Long student,
                        @RequestParam(required = false) Long group,
                        Model model) {
        List<ProblemId> ids = problemIds(problemIds);
        try {
            return switch (Addressee.of(student, group)) {
                case Addressee.ToStudent to ->
                        atAssignment(assignments.issueToStudent(to.student(), ids, dueDate, theoryScope).value());
                case Addressee.ToGroup to ->
                        atBatch(assignments.issueToGroup(to.group(), ids, dueDate, theoryScope).value());
            };
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("dueDate", dueDate);
            model.addAttribute("theoryScope", theoryScope);
            model.addAttribute("chosenStudent", student);
            model.addAttribute("chosenGroup", group);
            return form(problemIds, model);
        }
    }

    @GetMapping(Addresses.ASSIGNMENTS + "/{id}")
    public String assignment(@PathVariable long id, Model model) {
        return renderAssignment(new AssignmentId(id), model);
    }

    @PostMapping(Addresses.ASSIGNMENTS + "/{id}/due-date")
    public String changeDueDate(@PathVariable long id,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                                Model model) {
        AssignmentId assignmentId = new AssignmentId(id);
        try {
            assignments.changeDueDate(assignmentId, dueDate);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderAssignment(assignmentId, model);
        }
        return atAssignment(id);
    }

    /**
     * Удаляет Задание и возвращает к сводке: страницы больше нет, а Раздача,
     * если была, остаётся следом выдачи, а не списком живых Заданий.
     */
    @PostMapping(Addresses.ASSIGNMENTS + "/{id}/deletion")
    public String delete(@PathVariable long id, Model model) {
        AssignmentId assignmentId = new AssignmentId(id);
        try {
            assignments.delete(assignmentId);
        } catch (AssignmentInUseException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderAssignment(assignmentId, model);
        }
        return "redirect:" + Addresses.ASSIGNMENTS;
    }

    @GetMapping(Addresses.ASSIGNMENTS + "/batches/{id}")
    public String batch(@PathVariable long id, Model model) {
        return renderBatch(new AssignmentBatchId(id), model);
    }

    @PostMapping(Addresses.ASSIGNMENTS + "/batches/{id}/deletion")
    public String deleteBatch(@PathVariable long id, Model model) {
        AssignmentBatchId batchId = new AssignmentBatchId(id);
        try {
            assignments.deleteBatch(batchId);
        } catch (AssignmentInUseException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderBatch(batchId, model);
        }
        return "redirect:" + Addresses.ASSIGNMENTS;
    }

    private String renderForm(List<Long> problemIds, Model model) {
        model.addAttribute("problems", problems.problems(problemIds(problemIds)));
        model.addAttribute("students", students.all());
        model.addAttribute("groups", groups.all());
        model.addAttribute("scopes", TheoryScope.values());
        return "assignment/form";
    }

    private String renderAssignment(AssignmentId id, Model model) {
        ListedAssignment listed = assignments.assignment(id);
        model.addAttribute("listed", listed);
        model.addAttribute("problems", assignments.problemsOf(listed.assignment()));
        model.addAttribute("theory", assignments.theoryOf(listed.assignment()));
        return "assignment/assignment";
    }

    private String renderBatch(AssignmentBatchId id, Model model) {
        model.addAttribute("batch", assignments.batch(id));
        model.addAttribute("assignments", assignments.ofBatch(id));
        return "assignment/batch";
    }

    private static List<ProblemId> problemIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(ProblemId::new).toList();
    }

    private static String atAssignment(long id) {
        return "redirect:" + Addresses.ASSIGNMENTS + "/" + id;
    }

    private static String atBatch(long id) {
        return "redirect:" + Addresses.ASSIGNMENTS + "/batches/" + id;
    }
}
