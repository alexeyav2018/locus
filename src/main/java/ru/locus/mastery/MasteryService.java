package ru.locus.mastery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.assignment.Assignment;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentService;
import ru.locus.dictionary.SolutionMethod;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.problem.FoundProblem;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.student.Student;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyBranch;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyPath;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;
import ru.locus.work.ProblemPair;
import ru.locus.work.Solved;
import ru.locus.work.StudentWork;
import ru.locus.work.StudentWorkRepository;

/**
 * Простановка и показ отметок Владения — единственный вход области
 * для Учителя: ячейки на экране приёма по Заданию, выборочная
 * простановка по принятой Работе (ADR-0011, ADR-0039) и второй вход —
 * чтение распределения и пробелов по Ученику (`mastery-views`).
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
 * (показ ячеек) идёт в одну сторону; обратной нет (design.md). Второй вход
 * ({@link #overviewOf}), наоборот, зовёт {@link StudentService} напрямую —
 * кольца здесь нет: {@code StudentService} зависит от {@code List<StudentUsage>},
 * среди них {@link MasteryOfStudent}, а тот зависит от {@link MasteryRepository},
 * не от этого сервиса.
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
    private final StudentService students;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService solutionMethods;
    private final ProblemService problems;
    private final CurrentUser currentUser;

    public MasteryService(MasteryRepository marks,
                          AssignmentService assignments,
                          StudentWorkRepository works,
                          StudentService students,
                          TaxonomyService taxonomy,
                          SolutionMethodService solutionMethods,
                          ProblemService problems,
                          CurrentUser currentUser) {
        this.marks = marks;
        this.assignments = assignments;
        this.works = works;
        this.students = students;
        this.taxonomy = taxonomy;
        this.solutionMethods = solutionMethods;
        this.problems = problems;
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

    /**
     * Владение одного Ученика целиком: дерево от корней с распределением
     * каждого узла, таблица по Методу и перечень пробелов (`mastery-views`,
     * design.md).
     *
     * <p>Ячейки Темы — Методы из {@link ProblemService#findMethodsUsedByTopic()}
     * (разметка) ∪ Методы из суждений Ученика на этой Теме (замыкание
     * правила: суждение без разметки сегодня не возникает, но не должно
     * теряться молча, если появится). Ячейка без суждения — {@link
     * MasteryStatus#UNKNOWN}. Распределение Раздела — {@link
     * Distribution#plus} по потомкам (инвариант 8: ячейка принадлежит ровно
     * одной Теме, Тема — ровно одному родителю, двойного счёта нет).
     * Распределение Метода — {@code plus} по всем Темам, где он среди
     * ячеек; в таблицу попадают только Методы с {@code cells() > 0}.
     * Пробелы — суждения {@link MasteryStatus#NOT_MASTERED}, с полным
     * путём Темы ({@link TaxonomyService#paths()}) и именем Метода, по
     * пути Темы, затем по имени Метода.
     *
     * <p>На экран — по одному чтению дерева, путей, словаря Методов и пар
     * разметки (все — библиотека, без владельца, ADR-0027) и одному чтению
     * суждений Ученика ({@link MasteryRepository#findByStudent}, по
     * владельцу).
     *
     * @throws ru.locus.student.StudentNotFoundException чужой или
     *         несуществующий Ученик
     */
    @PreAuthorize("hasRole('TEACHER')")
    public MasteryOverview overviewOf(StudentId studentId) {
        UserId owner = owner();
        Student student = students.student(studentId);
        Map<Cell, MasteryStatus> statuses = marks.findByStudent(owner, student.id());
        Map<TaxonomyNodeId, List<SolutionMethodId>> pairsByTopic = problems.findMethodsUsedByTopic();

        Map<TaxonomyNodeId, LinkedHashSet<SolutionMethodId>> cellsByTopic = new LinkedHashMap<>();
        pairsByTopic.forEach((topic, methods) ->
                cellsByTopic.computeIfAbsent(topic, key -> new LinkedHashSet<>()).addAll(methods));
        statuses.keySet().forEach(cell ->
                cellsByTopic.computeIfAbsent(cell.topic(), key -> new LinkedHashSet<>()).add(cell.method()));

        Map<TaxonomyNodeId, Distribution> distributionByTopic = new LinkedHashMap<>();
        Map<SolutionMethodId, List<MasteryStatus>> statusesByMethod = new LinkedHashMap<>();
        cellsByTopic.forEach((topic, methods) -> {
            List<MasteryStatus> topicStatuses = new ArrayList<>();
            for (SolutionMethodId method : methods) {
                MasteryStatus status = statuses.getOrDefault(new Cell(topic, method), MasteryStatus.UNKNOWN);
                topicStatuses.add(status);
                statusesByMethod.computeIfAbsent(method, key -> new ArrayList<>()).add(status);
            }
            distributionByTopic.put(topic, Distribution.of(topicStatuses));
        });

        List<MasteryBranch> tree = new ArrayList<>();
        for (TaxonomyBranch root : taxonomy.tree()) {
            tree.add(buildBranch(root, distributionByTopic));
        }

        List<MasteryOfMethodRow> methodRows = new ArrayList<>();
        for (SolutionMethod method : solutionMethods.all()) {
            Distribution distribution = Distribution.of(statusesByMethod.getOrDefault(method.id(), List.of()));
            if (distribution.cells() > 0) {
                methodRows.add(new MasteryOfMethodRow(method, distribution));
            }
        }

        Map<SolutionMethodId, String> methodNames = new LinkedHashMap<>();
        solutionMethods.all().forEach(method -> methodNames.put(method.id(), method.name()));
        Map<TaxonomyNodeId, String> topicPaths = new LinkedHashMap<>();
        for (TaxonomyPath path : taxonomy.paths()) {
            topicPaths.put(path.id(), path.path());
        }
        List<Gap> gaps = new ArrayList<>();
        statuses.forEach((cell, status) -> {
            if (status == MasteryStatus.NOT_MASTERED) {
                gaps.add(new Gap(cell.topic(), topicPaths.get(cell.topic()),
                        cell.method(), methodNames.get(cell.method())));
            }
        });
        gaps.sort(Comparator.<Gap, String>comparing(Gap::topicPath).thenComparing(Gap::methodName));

        return new MasteryOverview(student, tree, methodRows, gaps);
    }

    /** Ветвь дерева владения — распределение Темы с ячеек, Раздела — сложением поддерева. */
    private static MasteryBranch buildBranch(TaxonomyBranch branch,
                                              Map<TaxonomyNodeId, Distribution> distributionByTopic) {
        List<MasteryBranch> children = new ArrayList<>();
        Distribution distribution = Distribution.empty();
        for (TaxonomyBranch child : branch.children()) {
            MasteryBranch built = buildBranch(child, distributionByTopic);
            children.add(built);
            distribution = distribution.plus(built.distribution());
        }
        if (branch.node().isTopic()) {
            distribution = distribution.plus(distributionByTopic.getOrDefault(branch.node().id(), Distribution.empty()));
        }
        return new MasteryBranch(branch.node(), distribution, children);
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
