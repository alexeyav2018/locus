package ru.locus.student;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;

/**
 * Экраны Учеников: список своих Учеников с формой заведения и карточка
 * с переименованием, Группами и удалением.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяет {@link StudentService} на каждой операции,
 * поэтому отказ происходит одинаково и при нажатии кнопки, и при обращении
 * по прямому адресу.
 *
 * Владельца контроллер <b>не знает</b>: ни из пути, ни из параметра, ни
 * из {@code CurrentUser}. Ученики — личный контур, и владельца подставляет
 * сервис (ADR-0027); появись он здесь — это был бы способ назначить
 * владельцем другого. Чужой Ученик неотличим от несуществующего:
 * {@link StudentNotFoundException} не перехватывается и отвечает 404
 * в обоих случаях.
 *
 * Признака роли для шаблонов тоже нет: экраны целиком доступны только
 * Учителю, и прятать на них нечего — Пользователь без роли до шаблона
 * не доходит.
 */
@Controller
public class StudentController {

    private final StudentService students;
    private final GroupService groups;

    public StudentController(StudentService students, GroupService groups) {
        this.students = students;
        this.groups = groups;
    }

    @GetMapping(Addresses.STUDENTS)
    public String list(Model model) {
        return renderList(model);
    }

    @PostMapping(Addresses.STUDENTS)
    public String create(@RequestParam(required = false) String name, Model model) {
        try {
            return atStudent(students.create(name).value());
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderList(model);
        }
    }

    @GetMapping(Addresses.STUDENTS + "/{id}")
    public String student(@PathVariable long id, Model model) {
        return renderStudent(new StudentId(id), model);
    }

    @PostMapping(Addresses.STUDENTS + "/{id}/name")
    public String rename(@PathVariable long id, @RequestParam(required = false) String name, Model model) {
        StudentId studentId = new StudentId(id);
        try {
            students.rename(studentId, name);
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderStudent(studentId, model);
        }
        return atStudent(id);
    }

    /**
     * Удаляет Ученика и возвращает к списку: карточки больше нет,
     * а места, к которому стоило бы вернуться, у плоского списка нет.
     */
    @PostMapping(Addresses.STUDENTS + "/{id}/deletion")
    public String delete(@PathVariable long id, Model model) {
        StudentId studentId = new StudentId(id);
        try {
            students.delete(studentId);
        } catch (StudentInUseException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderStudent(studentId, model);
        }
        return "redirect:" + Addresses.STUDENTS;
    }

    private String renderList(Model model) {
        model.addAttribute("students", students.all());
        return "student/list";
    }

    private String renderStudent(StudentId id, Model model) {
        Student student = students.student(id);
        model.addAttribute("student", student);
        model.addAttribute("groups", groups.groupsOf(student.id()));
        return "student/student";
    }

    private static String atStudent(long id) {
        return "redirect:" + Addresses.STUDENTS + "/" + id;
    }
}
