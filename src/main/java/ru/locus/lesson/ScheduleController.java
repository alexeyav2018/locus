package ru.locus.lesson;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;

/**
 * Расписание Учителя: {@code GET /schedule?week=YYYY-MM-DD} — неделя,
 * в которую попадает дата, без параметра — текущая.
 *
 * Контроллер тонкий (standards.md, «Слои и границы»): права, владелец
 * и «сегодня» — в {@link LessonService}; Пользователь без роли Учителя
 * получает 403 от сервиса.
 */
@Controller
public class ScheduleController {

    private final LessonService lessons;

    public ScheduleController(LessonService lessons) {
        this.lessons = lessons;
    }

    @GetMapping(Addresses.SCHEDULE)
    public String week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                       Model model) {
        model.addAttribute("week", lessons.week(week));
        return "schedule/week";
    }
}
