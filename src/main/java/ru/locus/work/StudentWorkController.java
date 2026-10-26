package ru.locus.work;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import ru.locus.Addresses;
import ru.locus.ShellAdvice;
import ru.locus.assignment.AssignmentId;
import ru.locus.file.FilePages;
import ru.locus.mastery.Cell;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;

/**
 * Экраны Работ: экран приёма по Заданию (С5) и список Работ Ученика.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяет {@link StudentWorkService} на каждой
 * операции, поэтому отказ одинаков и при нажатии кнопки, и по прямому
 * адресу.
 *
 * Владельца контроллер <b>не знает</b>: ни из пути, ни из параметра, ни
 * из {@code CurrentUser}. Работы — личный контур, владельца подставляет
 * сервис (ADR-0027); чужая Работа и чужое Задание неотличимы
 * от несуществующих — {@link StudentWorkNotFoundException}
 * и {@link ru.locus.assignment.AssignmentNotFoundException}
 * не перехватываются и отвечают 404 в обоих случаях.
 *
 * Отказы сервиса ({@link IllegalArgumentException}) и превышение предела
 * загрузки ({@link MaxUploadSizeExceededException}) показываются текстом
 * на экране приёма того Задания, откуда пришла форма. Отклонённая форма
 * приёма или вердикта показывается снова с введённым ({@link EnteredReview}):
 * вердиктом, примечанием, датой и значениями ячеек Владения, которые
 * приходят в тех же формах (`work-mastery-at-receipt`). Задание при приёме
 * стоит в адресе формы, а не только в теле: если тело не разобрано —
 * предел превышен, — из него ничего не прочитать, а адрес читается всегда.
 *
 * Даты из форм — {@code <input type="date">}, ISO {@code yyyy-MM-dd}.
 */
@Controller
public class StudentWorkController {

    private final StudentWorkService works;
    private final StudentService students;
    private final AssignmentScreen screen;
    private final ShellAdvice shell;
    private final FilePages filePages;

    public StudentWorkController(StudentWorkService works,
                                 StudentService students,
                                 AssignmentScreen screen,
                                 ShellAdvice shell,
                                 FilePages filePages) {
        this.works = works;
        this.students = students;
        this.screen = screen;
        this.shell = shell;
        this.filePages = filePages;
    }

    /**
     * Экран приёма по Заданию либо список Работ Ученика — по тому, какой
     * параметр указан; без обоих — к сводке Заданий: вход в Работы идёт
     * от Задания.
     */
    @GetMapping(Addresses.WORKS)
    public String screen(@RequestParam(required = false) Long assignment,
                         @RequestParam(required = false) Long student,
                         Model model) {
        if (assignment != null) {
            return renderAssignment(new AssignmentId(assignment), model);
        }
        if (student != null) {
            return renderStudent(new StudentId(student), model);
        }
        return "redirect:" + Addresses.ASSIGNMENTS;
    }

    @PostMapping(Addresses.WORKS)
    public String receive(@RequestParam long assignment,
                          @RequestParam long problem,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
                          @RequestParam(required = false) Verdict verdict,
                          @RequestParam(required = false) String note,
                          @RequestParam(name = "files", required = false) List<MultipartFile> files,
                          HttpServletRequest request,
                          Model model) {
        AssignmentId assignmentId = new AssignmentId(assignment);
        ProblemId problemId = new ProblemId(problem);
        List<Mark> marks = List.of();
        try {
            marks = marksFrom(request);
            works.receive(assignmentId, problemId, receivedOn, verdict, note, uploaded(files), marks);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("entered", new EnteredReview(problemId, verdict, note,
                    receivedOn == null ? null : receivedOn.toString(), chosen(marks)));
            return renderAssignment(assignmentId, model);
        }
        return atAssignment(assignmentId);
    }

    /**
     * Просмотр файла Работы внутри системы. Работа и её файлы читаются тем же
     * сервисом, что и экран приёма, — с владельцем из {@code CurrentUser}:
     * чужая Работа неотличима от несуществующей (404), как и файл, которого
     * в этой Работе нет.
     */
    @GetMapping(Addresses.WORKS + "/{id}/files/{fileId}")
    public String viewFile(@PathVariable long id, @PathVariable long fileId, Model model) {
        StudentWork work = works.work(new StudentWorkId(id));
        List<LinkedFile> linked = works.filesOf(work);
        for (int i = 0; i < linked.size(); i++) {
            if (linked.get(i).file().id().value() == fileId) {
                model.addAttribute("view", filePages.view("Работа: файл " + (i + 1),
                        linked.get(i).link().toString(), linked.get(i).file().key(),
                        Addresses.WORKS + "/" + id + "/files/" + fileId,
                        Addresses.WORKS + "?assignment=" + work.assignment().value()));
                return "file/viewer";
            }
        }
        throw new StudentWorkNotFoundException(work.id());
    }

    /**
     * Картинка страницы PDF из Работы (ADR-0052) — под теми же правами,
     * что страница просмотра: Работа читается сервисом с владельцем,
     * и картинка страницы чужой Работы неотличима от несуществующей.
     */
    @GetMapping(Addresses.WORKS + "/{id}/files/{fileId}/pages/{page}")
    public ResponseEntity<byte[]> filePage(@PathVariable long id, @PathVariable long fileId,
                                           @PathVariable int page, WebRequest request) {
        StudentWork work = works.work(new StudentWorkId(id));
        for (StudentWorkFile file : work.files()) {
            if (file.id().value() == fileId) {
                return filePages.image(file.key(), page, request);
            }
        }
        throw new StudentWorkNotFoundException(work.id());
    }

    @PostMapping(Addresses.WORKS + "/{id}/files")
    public String addFiles(@PathVariable long id,
                           @RequestParam(name = "files", required = false) List<MultipartFile> files,
                           Model model) {
        StudentWorkId workId = new StudentWorkId(id);
        AssignmentId assignmentId = works.work(workId).assignment();
        try {
            works.addFiles(workId, uploaded(files));
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderAssignment(assignmentId, model);
        }
        return atAssignment(assignmentId);
    }

    @PostMapping(Addresses.WORKS + "/{id}/files/{fileId}/deletion")
    public String deleteFile(@PathVariable long id, @PathVariable long fileId, Model model) {
        StudentWorkId workId = new StudentWorkId(id);
        AssignmentId assignmentId = works.work(workId).assignment();
        try {
            works.deleteFile(workId, new StudentWorkFileId(fileId));
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderAssignment(assignmentId, model);
        }
        return atAssignment(assignmentId);
    }

    /**
     * Вердикт, примечание и отметки Владения принятой Работы — одним
     * сохранением (блок «Вердикт и владение»). Отказ показывается на экране
     * приёма с введённым, вердикт при этом прежний: сервис откатывает всё.
     */
    @PostMapping(Addresses.WORKS + "/{id}/verdict")
    public String setVerdict(@PathVariable long id,
                             @RequestParam(required = false) Verdict verdict,
                             @RequestParam(required = false) String note,
                             HttpServletRequest request,
                             Model model) {
        StudentWorkId workId = new StudentWorkId(id);
        StudentWork work = works.work(workId);
        List<Mark> marks = List.of();
        try {
            marks = marksFrom(request);
            works.setVerdict(workId, verdict, note, marks);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("entered", new EnteredReview(work.problem(), verdict, note, null, chosen(marks)));
            return renderAssignment(work.assignment(), model);
        }
        return atAssignment(work.assignment());
    }

    @PostMapping(Addresses.WORKS + "/{id}/deletion")
    public String delete(@PathVariable long id) {
        StudentWorkId workId = new StudentWorkId(id);
        AssignmentId assignmentId = works.work(workId).assignment();
        works.delete(workId);
        return atAssignment(assignmentId);
    }

    /**
     * Предел загрузки превышен — Spring отклонил тело до того, как оно
     * дошло до обработчика. Экран приёма показывает это текстом, как отказ
     * сервиса; Задание берётся из адреса, потому что тело не разобрано.
     * Работа, к которой добавляли файлы, — из пути.
     *
     * Модель обработчика исключения чистая: {@code @ModelAttribute} из
     * {@link ShellAdvice} к ней не применяются, а экран рисуется в каркасе.
     * Поэтому шапка и подвал кладутся здесь явно.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, Model model) {
        model.addAttribute("shell", shell.shell(request));
        model.addAttribute("build", shell.build());
        model.addAttribute("error", "Файлы слишком велики: до 20 МБ на файл и до 100 МБ за один раз");
        AssignmentId assignmentId = assignmentFrom(request);
        if (assignmentId == null) {
            return "redirect:" + Addresses.ASSIGNMENTS;
        }
        return renderAssignment(assignmentId, model);
    }

    private AssignmentId assignmentFrom(HttpServletRequest request) {
        String assignment = request.getParameter("assignment");
        if (assignment != null && !assignment.isBlank()) {
            return new AssignmentId(Long.parseLong(assignment));
        }
        String path = request.getRequestURI();
        String prefix = request.getContextPath() + Addresses.WORKS + "/";
        if (path.startsWith(prefix)) {
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            return works.work(new StudentWorkId(Long.parseLong(id))).assignment();
        }
        return null;
    }

    /**
     * Отметки из формы ячеек — три параллельных списка {@code topic},
     * {@code method}, {@code status}. Читаются сырыми из запроса, а не через
     * {@code @RequestParam List<String>}: почему — в {@link Mark#fromForm}.
     * В запросе с файлами обычные поля доступны так же: к этому моменту
     * тело уже разобрано привязкой {@code files}.
     */
    private static List<Mark> marksFrom(HttpServletRequest request) {
        return Mark.fromForm(request.getParameterValues("topic"),
                             request.getParameterValues("method"),
                             request.getParameterValues("status"));
    }

    /**
     * Выбранные значения ячеек для повторного показа формы. Испорченная
     * форма ячеек отказывает ещё при разборе — тогда отметок нет, и форма
     * показывается с «без изменения» во всех ячейках.
     */
    private static Map<Cell, MasteryStatus> chosen(List<Mark> marks) {
        Map<Cell, MasteryStatus> chosen = new HashMap<>();
        for (Mark mark : marks) {
            if (mark.status() != null) {
                chosen.put(mark.cell(), mark.status());
            }
        }
        return chosen;
    }

    /** Экран приёма собирает {@link AssignmentScreen}: модель отказа та же, что у показа. */
    private String renderAssignment(AssignmentId id, Model model) {
        return screen.render(id, model);
    }

    private String renderStudent(StudentId id, Model model) {
        model.addAttribute("student", students.student(id));
        model.addAttribute("works", works.ofStudent(id));
        return "work/list";
    }

    /**
     * Файлы формы как они пришли, включая пустые поля без выбранного файла:
     * какие из них файлы, решает сервис — ему же и отказывать, если
     * не осталось ни одного.
     */
    private static List<UploadedWorkFile> uploaded(List<MultipartFile> files) {
        if (files == null) {
            return List.of();
        }
        List<UploadedWorkFile> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                uploaded.add(new UploadedWorkFile(file.getBytes(), file.getContentType()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return uploaded;
    }

    private static String atAssignment(AssignmentId id) {
        return AssignmentScreen.redirectTo(id);
    }
}
