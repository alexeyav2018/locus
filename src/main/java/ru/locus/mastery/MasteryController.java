package ru.locus.mastery;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.assignment.AssignmentId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.problem.ProblemId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.work.AssignmentScreen;

/**
 * Простановка отметок Владения с экрана приёма Работ (С5, ADR-0011).
 *
 * Своего экрана у отметок нет: ячейки показывает экран приёма
 * ({@link AssignmentScreen}), а сюда приходит только форма одной Задачи —
 * {@code assignment}, {@code problem} и три параллельных списка
 * {@code topic}, {@code method}, {@code status}, по элементу на ячейку
 * (design.md {@code mastery-marks}, «Форма ячеек»). Пустой {@code status} —
 * «без изменения», он превращается в {@link Mark} с {@code null}
 * и сервисом пропускается; {@code UNKNOWN} — снятие (ADR-0039).
 *
 * Списки читаются сырыми из {@link HttpServletRequest}, а не через
 * {@code @RequestParam List<String>}: конвертер Spring разбирает
 * единственное значение как список через запятую и пустую строку
 * превращает в пустой список — у Задачи с одной ячейкой «без изменения»
 * списки разошлись бы по длине, и форма ломалась бы молча.
 *
 * Контроллер тонкий (standards.md, «Слои и границы»): права, владелец
 * и все проверки — в {@link MasteryService}; отказ сервиса
 * ({@link IllegalArgumentException}) показывается текстом на экране
 * приёма того же Задания, как отказы форм Работ. Чужое Задание — 404
 * от сервиса, здесь не перехватывается.
 */
@Controller
public class MasteryController {

    private final MasteryService mastery;
    private final AssignmentScreen screen;

    public MasteryController(MasteryService mastery, AssignmentScreen screen) {
        this.mastery = mastery;
        this.screen = screen;
    }

    @PostMapping(Addresses.MASTERY)
    public String mark(@RequestParam long assignment,
                       @RequestParam long problem,
                       HttpServletRequest request,
                       Model model) {
        AssignmentId assignmentId = new AssignmentId(assignment);
        try {
            mastery.mark(assignmentId, new ProblemId(problem), marksFrom(request));
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return screen.render(assignmentId, model);
        }
        return AssignmentScreen.redirectTo(assignmentId);
    }

    /**
     * Три параллельных списка формы — в отметки по индексу. Разошедшиеся
     * длины — испорченная форма, а не отказ сервиса; неизвестное значение
     * шкалы отвергает {@link MasteryStatus#valueOf}.
     */
    private static List<Mark> marksFrom(HttpServletRequest request) {
        String[] topics = values(request, "topic");
        String[] methods = values(request, "method");
        String[] statuses = values(request, "status");
        if (topics.length != methods.length || topics.length != statuses.length) {
            throw new IllegalArgumentException("Форма ячеек повреждена: Темы, Методы и значения не сходятся по числу");
        }
        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < topics.length; i++) {
            MasteryStatus status = statuses[i].isBlank() ? null : MasteryStatus.valueOf(statuses[i]);
            marks.add(new Mark(new TaxonomyNodeId(Long.parseLong(topics[i])),
                               new SolutionMethodId(Long.parseLong(methods[i])),
                               status));
        }
        return marks;
    }

    private static String[] values(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? new String[0] : values;
    }
}
