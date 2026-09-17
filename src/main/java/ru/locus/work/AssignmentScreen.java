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
 * Вынесен из {@link StudentWorkController}, потому что экран рисуют два
 * контроллера: приём Работ и простановка отметок Владения
 * ({@code MasteryController}), у которой своего экрана нет — ячейки живут
 * в блоке принятой Работы (design.md {@code mastery-marks}). Отказ любой
 * из форм показывается текстом на том же экране, а не редиректом
 * с параметром: модель у отказа та же, что у показа, и собирать её
 * в двух местах — разъехаться молча.
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
     * Ячейки Владения ({@code cells}) — только у Задач с принятой Работой
     * (ADR-0011); шкала ({@code statuses}) — для списка выбора в форме.
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
