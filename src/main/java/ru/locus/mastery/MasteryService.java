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
import ru.locus.warmup.Warmup;
import ru.locus.warmup.WarmupId;
import ru.locus.warmup.WarmupNotFoundException;
import ru.locus.warmup.WarmupRepository;
import ru.locus.work.StudentWorkRepository;

/**
 * Простановка и показ отметок Владения — единственный вход области
 * для Учителя: ячейки на экране приёма по Заданию, выборочная
 * простановка по принятой Работе (ADR-0011, ADR-0039), то же на странице
 * Разминки — второе основание отметки (ADR-0061) — и второй вход —
 * чтение распределения и пробелов по Ученику (`mastery-views`).
 *
 * <p>Ячейки не хранятся, а вычисляются на каждый показ (standards.md,
 * «Вычислимое вычисляется в запросе»): у каждой Задачи Задания
 * кандидаты — {@code topics × methods} её разметки, в порядке разметки.
 * У Задачи без Работы они стоят в форме приёма: отметка ставится
 * «в момент проверки» (ADR-0011), и проверка идёт вместе с приёмом —
 * Работа и отметки записываются одной транзакцией
 * ({@code StudentWorkService.receive}). Подписи ячеек —
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
 * и через сервис отметок круг бы замкнулся. К тому же сам
 * {@code StudentWorkService} зовёт этот сервис — отметки пишутся в одной
 * транзакции с приёмом Работы и её вердиктом (`work-mastery-at-receipt`),
 * и зависимость между сервисами идёт в одну сторону. Итоги Разминок для
 * справки — так же из {@link WarmupRepository}, а не из сервиса Разминок:
 * тот зовёт этот сервис при сохранении итогов и отметок одним действием
 * (ADR-0061), и обратная зависимость замкнула бы кольцо. Второй вход
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
    private final WarmupRepository warmups;
    private final StudentService students;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService solutionMethods;
    private final ProblemService problems;
    private final CurrentUser currentUser;

    public MasteryService(MasteryRepository marks,
                          AssignmentService assignments,
                          StudentWorkRepository works,
                          WarmupRepository warmups,
                          StudentService students,
                          TaxonomyService taxonomy,
                          SolutionMethodService solutionMethods,
                          ProblemService problems,
                          CurrentUser currentUser) {
        this.marks = marks;
        this.assignments = assignments;
        this.works = works;
        this.warmups = warmups;
        this.students = students;
        this.taxonomy = taxonomy;
        this.solutionMethods = solutionMethods;
        this.problems = problems;
        this.currentUser = currentUser;
    }

    /**
     * Ячейки владения по Заданию — у каждой Задачи состава, с Работой
     * и без, в порядке выдачи Задач; внутри Задачи — Темы в порядке
     * разметки × Методы в порядке разметки.
     *
     * <p>На весь экран — по одному запросу на значения
     * ({@link MasteryRepository#findByStudent}) и на каждое основание
     * справки ({@link StudentWorkRepository#countCheckedByPairs},
     * {@link WarmupRepository#countOutcomesByPairs}), а не по запросу
     * на ячейку. Справка считает проверенные Работы Ученика по ВСЕМ его
     * Заданиям, не только этому, и Итоги всех его Разминок: она о Ученике
     * и паре, а не о Задании.
     * Ячейка без строки — {@link MasteryStatus#UNKNOWN} (ADR-0039).
     *
     * @throws ru.locus.assignment.AssignmentNotFoundException чужое или
     *         несуществующее Задание
     */
    @PreAuthorize("hasRole('TEACHER')")
    public Map<ProblemId, List<MasteryCell>> cellsOf(AssignmentId assignmentId) {
        UserId owner = owner();
        Assignment assignment = assignments.assignment(assignmentId).assignment();
        Map<ProblemId, List<MasteryCell>> cells = new LinkedHashMap<>();
        Map<Cell, MasteryStatus> statuses = marks.findByStudent(owner, assignment.student());
        Map<Cell, Solved> byWorks = works.countCheckedByPairs(owner, assignment.student());
        Map<Cell, Solved> byWarmups = warmups.countOutcomesByPairs(owner, assignment.student());
        for (FoundProblem found : assignments.problemsOf(assignment)) {
            Problem problem = found.problem();
            List<MasteryCell> ofProblem = new ArrayList<>();
            for (int t = 0; t < problem.topics().size(); t++) {
                TaxonomyNodeId topic = problem.topics().get(t);
                for (int m = 0; m < problem.methods().size(); m++) {
                    SolutionMethodId method = problem.methods().get(m);
                    Cell cell = new Cell(topic, method);
                    ofProblem.add(new MasteryCell(topic, found.topicPaths().get(t),
                            method, found.methodNames().get(m),
                            statuses.getOrDefault(cell, MasteryStatus.UNKNOWN),
                            byWorks.getOrDefault(cell, Solved.NONE),
                            byWarmups.getOrDefault(cell, Solved.NONE)));
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
     * Ячейки владения по Разминке — у каждого участника, в порядке
     * участников (ADR-0061).
     *
     * <p>Ячейки участника — объединение пар всех Задач Разминки, каждая
     * пара один раз: Задачи в порядке состава, внутри — Темы в порядке
     * разметки × Методы в порядке разметки; повторная пара остаётся там,
     * где встретилась впервые. Повторять её у каждой Задачи нельзя: одна
     * ячейка дважды в одной форме дала бы два значения одной отметке.
     * Значение и справка по обоим основаниям — как в {@link #cellsOf}:
     * по одному запросу на значения и на каждое основание у каждого
     * участника.
     *
     * <p>Разминка читается {@link WarmupRepository} с владельцем вошедшего:
     * чужая неотличима от несуществующей.
     *
     * @throws WarmupNotFoundException чужая или несуществующая Разминка
     */
    @PreAuthorize("hasRole('TEACHER')")
    public Map<StudentId, List<MasteryCell>> cellsOfWarmup(WarmupId warmupId) {
        UserId owner = owner();
        Warmup warmup = existingWarmup(owner, warmupId);
        List<PairOfWarmup> pairs = pairsOf(problems.problems(warmup.problems()));
        Map<StudentId, List<MasteryCell>> cells = new LinkedHashMap<>();
        for (StudentId student : warmup.students()) {
            Map<Cell, MasteryStatus> statuses = marks.findByStudent(owner, student);
            Map<Cell, Solved> byWorks = works.countCheckedByPairs(owner, student);
            Map<Cell, Solved> byWarmups = warmups.countOutcomesByPairs(owner, student);
            List<MasteryCell> ofStudent = new ArrayList<>();
            for (PairOfWarmup pair : pairs) {
                Cell cell = pair.cell();
                ofStudent.add(new MasteryCell(cell.topic(), pair.topicPath(), cell.method(), pair.methodName(),
                        statuses.getOrDefault(cell, MasteryStatus.UNKNOWN),
                        byWorks.getOrDefault(cell, Solved.NONE),
                        byWarmups.getOrDefault(cell, Solved.NONE)));
            }
            cells.put(student, ofStudent);
        }
        return cells;
    }

    /**
     * Выборочная простановка участнику Разминки — второе основание отметки
     * (ADR-0061); устроена как {@link #mark}: {@code null} у
     * {@link Mark#status()} — «без изменения», {@link MasteryStatus#UNKNOWN}
     * снимает отметку, все проверки до первой записи, одна транзакция.
     *
     * <p>Основание проверяется здесь, а не у вызывающего: Разминка своя,
     * Ученик — её участник, каждая пара — из разметки хотя бы одной Задачи
     * Разминки. Метода «отметить по набору Задач, переданному вызывающим»
     * нет намеренно: с ним правило «отметка только по основанию» ушло бы
     * из области отметок, и любой сервис мог бы отметить по любым Задачам.
     * Итоги Разминки ни на что здесь не влияют и не читаются (ADR-0014).
     *
     * @throws WarmupNotFoundException чужая или несуществующая Разминка
     * @throws IllegalArgumentException Ученик не участник, пара не из Задач
     *         Разминки
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void markByWarmup(WarmupId warmupId, StudentId student, List<Mark> markList) {
        UserId owner = owner();
        Warmup warmup = existingWarmup(owner, warmupId);
        if (!warmup.hasStudent(student)) {
            throw new IllegalArgumentException("Этот Ученик в Разминке не участвует");
        }
        Set<Cell> candidates = new HashSet<>();
        for (PairOfWarmup pair : pairsOf(problems.problems(warmup.problems()))) {
            candidates.add(pair.cell());
        }
        List<Mark> effective = new ArrayList<>();
        for (Mark mark : markList) {
            if (!candidates.contains(mark.cell())) {
                throw new IllegalArgumentException("Ячейки нет: пара не из разметки Задач Разминки");
            }
            if (mark.status() != null) {
                effective.add(mark);
            }
        }
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

    /**
     * Пары Задач Разминки — объединение без повторов, в порядке состава
     * и разметки, с подписями первого вхождения.
     */
    private static List<PairOfWarmup> pairsOf(List<FoundProblem> found) {
        Map<Cell, PairOfWarmup> pairs = new LinkedHashMap<>();
        for (FoundProblem problem : found) {
            List<TaxonomyNodeId> topics = problem.problem().topics();
            List<SolutionMethodId> methods = problem.problem().methods();
            for (int t = 0; t < topics.size(); t++) {
                for (int m = 0; m < methods.size(); m++) {
                    Cell cell = new Cell(topics.get(t), methods.get(m));
                    pairs.putIfAbsent(cell, new PairOfWarmup(cell,
                            problem.topicPaths().get(t), problem.methodNames().get(m)));
                }
            }
        }
        return List.copyOf(pairs.values());
    }

    private Warmup existingWarmup(UserId owner, WarmupId id) {
        return warmups.findById(owner, id).orElseThrow(() -> new WarmupNotFoundException(id));
    }

    /** Пара Задач Разминки с подписями — заготовка ячейки до значения и справки. */
    private record PairOfWarmup(Cell cell, String topicPath, String methodName) {
    }

    private UserId owner() {
        return currentUser.id();
    }
}
