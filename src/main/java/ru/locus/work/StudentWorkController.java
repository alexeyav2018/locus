package ru.locus.work;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import ru.locus.Addresses;
import ru.locus.assignment.AssignmentId;
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
 * на экране приёма того Задания, откуда пришла форма. Задание при приёме
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

    public StudentWorkController(StudentWorkService works,
                                 StudentService students,
                                 AssignmentScreen screen) {
        this.works = works;
        this.students = students;
        this.screen = screen;
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
                          Model model) {
        AssignmentId assignmentId = new AssignmentId(assignment);
        try {
            works.receive(assignmentId, new ProblemId(problem), receivedOn, verdict, note, uploaded(files));
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderAssignment(assignmentId, model);
        }
        return atAssignment(assignmentId);
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

    @PostMapping(Addresses.WORKS + "/{id}/verdict")
    public String setVerdict(@PathVariable long id,
                             @RequestParam(required = false) Verdict verdict,
                             @RequestParam(required = false) String note) {
        StudentWorkId workId = new StudentWorkId(id);
        AssignmentId assignmentId = works.work(workId).assignment();
        works.setVerdict(workId, verdict, note);
        return atAssignment(assignmentId);
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
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, Model model) {
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
     * Экран приёма собирает {@link AssignmentScreen}: тот же экран рисует
     * и {@code MasteryController} — при отказе в простановке отметок.
     */
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
