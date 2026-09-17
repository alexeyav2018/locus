package ru.locus.mastery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.assignment.Assignment;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.problem.FoundProblem;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;
import ru.locus.work.ProblemPair;
import ru.locus.work.Solved;
import ru.locus.work.StudentWork;
import ru.locus.work.StudentWorkRepository;

/**
 * Простановка и показ отметок Владения — единственный вход области
 * для Учителя: ячейки на экране приёма по Заданию и выборочная
 * простановка по принятой Работе (ADR-0011, ADR-0039).
 *
 * <p>Ячейки не хранятся, а вычисляются на каждый показ (standards.md,
 * «Вычислимое вычисляется в запросе»): у Задачи с принятой Работой
 * кандидаты — {@code topics × methods} её разметки, в порядке разметки;
 * у Задачи без Работы ячеек нет — отметка ставится «точечно, в момент
 * проверки» (ADR-0011), а до Работы проверять нечего. Подписи ячеек —
 * пути Тем и имена Методов — уже есть у {@link FoundProblem}, который
 * экран приёма и так получает от {@link AssignmentService#problemsOf};
 * дерево и словарь здесь не спрашиваются.
 *
 * <p>Права проверяются здесь, а не в контроллере (standards.md, «Слои
 * и границы»); каждая операция — только Учителю, включая чтение.
 * Отметки — ЛИЧНЫЙ контур: владелец берётся у {@link CurrentUser}
 * в начале операции и уходит в репозиторий, из запроса он не читается.
 * Задание берётся у {@link AssignmentService#assignment} от имени того же
 * вошедшего, так что чужое Задание неотличимо от несуществующего —
 * {@code AssignmentNotFoundException}, как при приёме Работы.
 *
 * <p>Работы читаются из {@link StudentWorkRepository}, а не из
 * {@code StudentWorkService}: тот тянет {@code StudentService}, который
 * собирает ответчиков {@code StudentUsage}, среди них {@link MasteryOfStudent} —
 * и через сервис отметок круг бы замкнулся. Зависимость {@code work → mastery}
 * (показ ячеек) идёт в одну сторону; обратной нет (design.md).
 *
 * <p>Сервис никогда не зовёт методы репозитория без владельца
 * ({@code countByTopic}, {@code countByMethod}, {@code rehomeTopic},
 * {@code deleteByTopic}): те — ответы дереву и словарю под правом
 * Администратора (ADR-0036), Учителю они не принадлежат.
 */
@Service
public class MasteryService {

    private final MasteryRepository marks;
    private final AssignmentService assignments;
    private final StudentWorkRepository works;
    private final CurrentUser currentUser;

    public MasteryService(MasteryRepository marks,
                          AssignmentService assignments,
                          StudentWorkRepository works,
                          CurrentUser currentUser) {
        this.marks = marks;
        this.assignments = assignments;
        this.works = works;
        this.currentUser = currentUser;
    }

    /**
     * Ячейки владения по Заданию — только у Задач, по которым принята
     * Работа, в порядке выдачи Задач; внутри Задачи — Темы в порядке
     * разметки × Методы в порядке разметки. У Задачи без Работы ключа
     * в карте нет.
     *
     * <p>На весь экран — по одному запросу на значения
     * ({@link MasteryRepository#findByStudent}) и на справку
     * ({@link StudentWorkRepository#countCheckedByPairs}), а не по запросу
     * на ячейку. Справка считает проверенные Работы Ученика по ВСЕМ его
     * Заданиям, не только этому: она о Ученике и паре, а не о Задании.
     * Ячейка без строки — {@link MasteryStatus#UNKNOWN} (ADR-0039).
     *
     * @throws ru.locus.assignment.AssignmentNotFoundException чужое или
     *         несуществующее Задание
     */
    @PreAuthorize("hasRole('TEACHER')")
    public Map<ProblemId, List<MasteryCell>> cellsOf(AssignmentId assignmentId) {
        UserId owner = owner();
        Assignment assignment = assignments.assignment(assignmentId).assignment();
        Set<ProblemId> withWork = new HashSet<>();
        for (StudentWork work : works.findByAssignment(owner, assignment.id())) {
            withWork.add(work.problem());
        }
        Map<ProblemId, List<MasteryCell>> cells = new LinkedHashMap<>();
        if (withWork.isEmpty()) {
            return cells;
        }
        Map<Cell, MasteryStatus> statuses = marks.findByStudent(owner, assignment.student());
        Map<ProblemPair, Solved> hints = works.countCheckedByPairs(owner, assignment.student());
        for (FoundProblem found : assignments.problemsOf(assignment)) {
            Problem problem = found.problem();
            if (!withWork.contains(problem.id())) {
                continue;
            }
            List<MasteryCell> ofProblem = new ArrayList<>();
            for (int t = 0; t < problem.topics().size(); t++) {
                TaxonomyNodeId topic = problem.topics().get(t);
                for (int m = 0; m < problem.methods().size(); m++) {
                    SolutionMethodId method = problem.methods().get(m);
                    Solved solved = hints.getOrDefault(new ProblemPair(topic, method), new Solved(0, 0));
                    ofProblem.add(new MasteryCell(topic, found.topicPaths().get(t),
                            method, found.methodNames().get(m),
                            statuses.getOrDefault(new Cell(topic, method), MasteryStatus.UNKNOWN),
                            solved.correct(), solved.checked()));
                }
            }
            cells.put(problem.id(), ofProblem);
        }
        return cells;
    }

    /**
     * Выборочная простановка по одной Задаче Задания: каждая отметка
     * ставится на свою ячейку, {@link Mark#status()} {@code null} — «без
     * изменения», пропускается; {@link MasteryStatus#UNKNOWN} снимает
     * отметку (ADR-0039). Нетронутые ячейки остаются как были.
     *
     * <p>Все проверки — до первой записи, и вся простановка в одной
     * транзакции: либо принимаются все отметки, либо ни одна — Учитель,
     * получив отказ, не должен гадать, какие из ячеек всё же изменились.
     * Проверяется: Задача в составе Задания; по ней принята Работа
     * (отметка ставится при проверке, ADR-0011); каждая пара — из разметки
     * этой Задачи (ячейки порождаются употреблением, инвариант 4;
     * произведение чужих Тем и Методов ячейкой не является).
     * Ученик — у Задания; вердикт Работы ни на что здесь не влияет
     * и не читается (ADR-0014).
     *
     * @throws ru.locus.assignment.AssignmentNotFoundException чужое или
     *         несуществующее Задание
     * @throws IllegalArgumentException Задача не из состава, Работы нет,
     *         пара не из разметки Задачи
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void mark(AssignmentId assignmentId, ProblemId problemId, List<Mark> markList) {
        UserId owner = owner();
        Assignment assignment = assignments.assignment(assignmentId).assignment();
        if (!assignment.problems().contains(problemId)) {
            throw new IllegalArgumentException("Такой Задачи в Задании нет");
        }
        boolean received = works.findByAssignment(owner, assignment.id()).stream()
                .anyMatch(work -> work.problem().equals(problemId));
        if (!received) {
            throw new IllegalArgumentException("Отметки ставятся по принятой Работе, а по этой Задаче Работы нет");
        }
        Set<Cell> candidates = candidates(assignments.problemsOf(assignment), problemId);
        List<Mark> effective = new ArrayList<>();
        for (Mark mark : markList) {
            if (!candidates.contains(mark.cell())) {
                throw new IllegalArgumentException("Ячейки нет: пара не из разметки Задачи");
            }
            if (mark.status() != null) {
                effective.add(mark);
            }
        }
        StudentId student = assignment.student();
        for (Mark mark : effective) {
            marks.put(owner, student, mark.topic(), mark.method(), mark.status());
        }
    }

    /** Ячейки-кандидаты Задачи — произведение её Тем и Методов. */
    private static Set<Cell> candidates(List<FoundProblem> problems, ProblemId problemId) {
        Set<Cell> candidates = new HashSet<>();
        for (FoundProblem found : problems) {
            if (!found.problem().id().equals(problemId)) {
                continue;
            }
            for (TaxonomyNodeId topic : found.problem().topics()) {
                for (SolutionMethodId method : found.problem().methods()) {
                    candidates.add(new Cell(topic, method));
                }
            }
        }
        return candidates;
    }

    private UserId owner() {
        return currentUser.id();
    }
}
