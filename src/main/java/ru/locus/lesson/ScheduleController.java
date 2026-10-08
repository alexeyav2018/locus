package ru.locus.lesson;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import ru.locus.Addresses;
import ru.locus.ReturnTo;
import ru.locus.student.GroupId;
import ru.locus.student.GroupService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;

/**
 * Расписание Учителя: {@code GET /schedule?week=YYYY-MM-DD} — неделя,
 * в которую попадает дата, без параметра — текущая; форма заведения
 * Занятия, его карточка с правкой и удалением.
 *
 * Контроллер тонкий (standards.md, «Слои и границы»): права, владелец
 * и «сегодня» — в {@link LessonService}; Пользователь без роли Учителя
 * получает 403 от сервиса. Чужое Занятие неотличимо от несуществующего —
 * {@link LessonNotFoundException} не перехватывается и отвечает 404.
 *
 * Правило во времени собирается здесь из полей формы, как
 * {@code AssignmentFilter} в {@code AssignmentController}: его проверки
 * живут в {@link LessonTiming}, и отказ любого рода возвращает ту же форму
 * с сообщением. Даты — {@code <input type="date">}, время —
 * {@code <input type="time">}, оба формата названы явно.
 *
 * Встреча открывается по Занятию и плановой дате — её ключу (ADR-0048);
 * перенос, отмена, возврат и неявка — отдельные действия на её странице.
 */
@Controller
public class ScheduleController {

    private static final String LESSONS = Addresses.SCHEDULE + "/lessons";

    private static final String MEETING = LESSONS + "/{id}/meetings/{date}";

    private final LessonService lessons;
    private final StudentService students;
    private final GroupService groups;

    public ScheduleController(LessonService lessons, StudentService students, GroupService groups) {
        this.lessons = lessons;
        this.students = students;
        this.groups = groups;
    }

    @GetMapping(Addresses.SCHEDULE)
    public String week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                       Model model) {
        model.addAttribute("week", lessons.week(week));
        return "schedule/week";
    }

    /**
     * Форма заведения; дата первой встречи подставлена из выбранного дня.
     * Выбывшие Ученики не предлагаются (ADR-0040).
     */
    @GetMapping(LESSONS + "/new")
    public String form(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        model.addAttribute("firstDate", date);
        return renderForm(model);
    }

    @PostMapping(LESSONS)
    public String create(@RequestParam(required = false) Long student,
                         @RequestParam(required = false) Long group,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate firstDate,
                         @RequestParam(defaultValue = "false") boolean weekly,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastDate,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                         @RequestParam(required = false) Integer durationMinutes,
                         Model model) {
        try {
            LessonTiming timing = LessonTiming.of(firstDate, lastDate, weekly, start, durationMinutes);
            lessons.create(student == null ? null : new StudentId(student),
                    group == null ? null : new GroupId(group), timing);
            return "redirect:" + Addresses.SCHEDULE + "?week=" + timing.firstDate();
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("chosenStudent", student);
            model.addAttribute("chosenGroup", group);
            model.addAttribute("firstDate", firstDate);
            model.addAttribute("weekly", weekly);
            model.addAttribute("lastDate", lastDate);
            model.addAttribute("start", start);
            model.addAttribute("durationMinutes", durationMinutes);
            return renderForm(model);
        }
    }

    @GetMapping(LESSONS + "/{id}")
    public String lesson(@PathVariable long id, Model model) {
        return renderLesson(new LessonId(id), model);
    }

    /**
     * Правка; после неё — карточка Занятия, которое действует дальше:
     * при делении это новое Занятие (ADR-0047).
     */
    @PostMapping(LESSONS + "/{id}")
    public String change(@PathVariable long id,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestParam(required = false) DayOfWeek dayOfWeek,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                         @RequestParam(required = false) Integer durationMinutes,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastDate,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
                         Model model) {
        LessonId lessonId = new LessonId(id);
        try {
            LessonId current = lessons.change(lessonId,
                    new LessonChange(date, dayOfWeek, start, durationMinutes, lastDate), effectiveFrom);
            return "redirect:" + LESSONS + "/" + current.value();
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderLesson(lessonId, model);
        }
    }

    /** Удаляет Занятие и возвращает к неделе его первой встречи. */
    @PostMapping(LESSONS + "/{id}/deletion")
    public String delete(@PathVariable long id) {
        LessonId lessonId = new LessonId(id);
        LocalDate firstDate = lessons.lesson(lessonId).lesson().timing().firstDate();
        lessons.delete(lessonId);
        return "redirect:" + Addresses.SCHEDULE + "?week=" + firstDate;
    }

    /** Страница Встречи по Занятию и плановой дате; даты без Встречи — 404 (ADR-0048). */
    @GetMapping(MEETING)
    public String meeting(@PathVariable long id,
                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          Model model) {
        return renderMeeting(new LessonId(id), date, model);
    }

    @PostMapping(MEETING + "/move")
    public String move(@PathVariable long id,
                       @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate movedDate,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                       @RequestParam(required = false) Integer durationMinutes,
                       @RequestParam(required = false) String from,
                       Model model) {
        return adjust(new LessonId(id), date, from, model,
                () -> lessons.move(new LessonId(id), date, MeetingAdjustment.Move.of(movedDate, start, durationMinutes)));
    }

    @PostMapping(MEETING + "/cancellation")
    public String cancel(@PathVariable long id,
                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestParam(required = false) String from,
                         Model model) {
        return adjust(new LessonId(id), date, from, model, () -> lessons.cancel(new LessonId(id), date));
    }

    @PostMapping(MEETING + "/restoration")
    public String restore(@PathVariable long id,
                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) String from,
                          Model model) {
        return adjust(new LessonId(id), date, from, model, () -> lessons.restore(new LessonId(id), date));
    }

    @PostMapping(MEETING + "/absence")
    public String absence(@PathVariable long id,
                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam boolean absent,
                          @RequestParam(required = false) String from,
                          Model model) {
        return adjust(new LessonId(id), date, from, model, () -> lessons.markAbsence(new LessonId(id), date, absent));
    }

    /**
     * Действие над Встречей; после него — та же страница Встречи с прежним
     * возвратом, при отказе — она же с сообщением и раскрытым блоком
     * отклонённого действия (ADR-0042).
     */
    private String adjust(LessonId id, LocalDate date, String from, Model model, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderMeeting(id, date, model);
        }
        UriComponentsBuilder page = UriComponentsBuilder.fromPath(LESSONS + "/{id}/meetings/{date}");
        ReturnTo.safe(from).ifPresent(back -> page.queryParam("from", back));
        return "redirect:" + page.buildAndExpand(id.value(), date).encode().toUriString();
    }

    private String renderMeeting(LessonId id, LocalDate date, Model model) {
        model.addAttribute("details", lessons.meeting(id, date));
        return "schedule/meeting";
    }

    private String renderForm(Model model) {
        model.addAttribute("students", students.active());
        model.addAttribute("groups", groups.all());
        return "schedule/form";
    }

    private String renderLesson(LessonId id, Model model) {
        ListedLesson listed = lessons.lesson(id);
        model.addAttribute("listed", listed);
        model.addAttribute("dayName", Week.dayName(listed.lesson().timing().dayOfWeek()));
        model.addAttribute("dayNames", dayNames());
        return "schedule/lesson";
    }

    private static Map<DayOfWeek, String> dayNames() {
        Map<DayOfWeek, String> names = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            names.put(day, Week.dayName(day));
        }
        return names;
    }
}
