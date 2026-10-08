package ru.locus;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.locus.assignment.AssignmentFilter;
import ru.locus.assignment.AssignmentService;
import ru.locus.lesson.LessonService;
import ru.locus.student.StudentService;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;
import ru.locus.user.User;

/**
 * Главная страница вошедшего.
 *
 * Показывает, кто вошёл, и ведёт к разделам: общая библиотека — всем,
 * Ученики и Группы — Учителю, учётные записи — Администратору. Учителю
 * вдобавок — то, что требует внимания сегодня: Встречи дня и несданные Задания.
 *
 * Ссылки показываются по ролям, но правами это не является: настоящая
 * проверка стоит на методах сервисов, и обращение по прямому адресу
 * отклоняется независимо от разметки.
 */
@Controller
public class HomeController {

    /** Сколько несданных Заданий показывает главная: остальные — по ссылке на сводку. */
    private static final int OVERDUE_SHOWN = 5;

    private final CurrentUser currentUser;
    private final AssignmentService assignments;
    private final StudentService students;
    private final LessonService lessons;

    public HomeController(CurrentUser currentUser, AssignmentService assignments, StudentService students,
                          LessonService lessons) {
        this.currentUser = currentUser;
        this.assignments = assignments;
        this.students = students;
        this.lessons = lessons;
    }

    @GetMapping(Addresses.HOME)
    public String show(Model model) {
        User user = currentUser.account();
        model.addAttribute("login", user.login());
        model.addAttribute("administrator", user.hasRole(Role.ADMINISTRATOR));
        boolean teacher = user.hasRole(Role.TEACHER);
        model.addAttribute("teacher", teacher);
        if (teacher) {
            // Личное Учителя: сервисы сами отбирают по владельцу (ADR-0027).
            var notSubmitted = assignments.list(new AssignmentFilter(null, null, null, null, true));
            model.addAttribute("notSubmittedCount", notSubmitted.size());
            model.addAttribute("notSubmitted", notSubmitted.stream().limit(OVERDUE_SHOWN).toList());
            model.addAttribute("studentCount", students.active().size());
            model.addAttribute("today", lessons.today());
        }
        return "home";
    }
}
