package ru.locus.work;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import ru.locus.Addresses;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentService;
import ru.locus.assignment.ListedAssignment;
import ru.locus.mastery.MasteryService;
import ru.locus.mastery.MasteryStatus;
import ru.locus.problem.ProblemId;

/**
 * Экран приёма по Заданию (С5) — сборка модели для {@code work/assignment}.
 *
 * Вынесен из {@link StudentWorkController}, потому что экран рисуется
 * из многих его обработчиков: показ и отказ каждой формы — приёма,
 * вердикта с отметками Владения, файлов. Отказ показывается текстом
 * на том же экране, а не редиректом с параметром: модель у отказа та же,
 * что у показа, и собирать её в нескольких местах — разъехаться молча.
 * Отметки Владения своего экрана и адреса не имеют: ячейки стоят в форме
 * приёма и в блоке «Вердикт и владение» (`work-mastery-at-receipt`).
 *
 * Правила для контроллера действуют и здесь (standards.md, «Слои
 * и границы»): ни репозитория, ни хранилища, ни {@code CurrentUser} —
 * всё через сервисы, которые сами проверяют роль и подставляют владельца
 * (ADR-0027). Сторожит {@code StudentWorkControllerIsThinTest}.
 */
@Component
public class AssignmentScreen {

    private final StudentWorkService works;
    private final AssignmentService assignments;
    private final MasteryService mastery;

    public AssignmentScreen(StudentWorkService works,
                            AssignmentService assignments,
                            MasteryService mastery) {
        this.works = works;
        this.assignments = assignments;
        this.mastery = mastery;
    }

    /**
     * Наполняет модель и возвращает имя шаблона. Атрибут {@code error},
     * если он нужен, вызывающий кладёт сам — до или после, порядок
     * не важен.
     *
     * Ячейки Владения ({@code cells}) — у каждой Задачи Задания: и в форме
     * приёма, и у принятой Работы; шкала ({@code statuses}) — для списка
     * выбора в форме. Введённое в отклонённую форму ({@code entered},
     * {@link EnteredReview}) кладёт вызывающий.
     *
     * @throws ru.locus.assignment.AssignmentNotFoundException чужое или
     *         несуществующее Задание — 404, как везде в личном контуре
     */
    public String render(AssignmentId id, Model model) {
        ListedAssignment listed = assignments.assignment(id);
        Map<ProblemId, StudentWork> byProblem = works.ofAssignment(id);
        Map<ProblemId, List<LinkedFile>> files = new LinkedHashMap<>();
        for (StudentWork work : byProblem.values()) {
            files.put(work.problem(), works.filesOf(work));
        }
        model.addAttribute("listed", listed);
        model.addAttribute("problems", assignments.problemsOf(listed.assignment()));
        model.addAttribute("works", byProblem);
        model.addAttribute("files", files);
        model.addAttribute("verdicts", Verdict.values());
        model.addAttribute("today", works.today());
        model.addAttribute("cells", mastery.cellsOf(id));
        model.addAttribute("statuses", MasteryStatus.values());
        return "work/assignment";
    }

    /** Редирект на экран приёма — после удавшегося действия любой из форм. */
    public static String redirectTo(AssignmentId id) {
        return "redirect:" + Addresses.WORKS + "?assignment=" + id.value();
    }
}
