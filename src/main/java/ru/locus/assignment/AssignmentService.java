package ru.locus.assignment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.problem.FoundProblem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.student.Group;
import ru.locus.student.GroupId;
import ru.locus.student.GroupService;
import ru.locus.student.Student;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.theory.NodeTheory;
import ru.locus.theory.TheoryMaterialId;
import ru.locus.theory.TheoryService;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Ведение Заданий и Раздач: выдача, показ с вычисленным «не сдано», теория
 * по охвату, перенос срока и удаление.
 *
 * <p>Права проверяются здесь, а не в контроллере и не на адресах
 * (standards.md, «Слои и границы»); каждая операция — только Учителю,
 * включая чтение: у Пользователя без этой роли Заданий нет, и список
 * для него не пуст, а недоступен.
 *
 * <p>Задания — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027): владелец берётся у {@link CurrentUser} в начале каждой операции
 * и передаётся в репозитории, из запроса он не читается никогда. Чужое
 * Задание и несуществующее неразличимы — репозиторий отвечает пусто
 * в обоих случаях, наружу уходит один {@link AssignmentNotFoundException}.
 * Ученик и Группа берутся у {@link StudentService} и {@link GroupService}
 * от имени того же вошедшего, так что чужой адресат для выдачи —
 * несуществующий. Задачи и теория, которые Задание показывает, — общая
 * библиотека, и их отдают библиотечные сервисы без владельца.
 *
 * <p>«Сегодня» берётся у бина {@link Clock} ({@code TimeConfiguration}),
 * а не у {@code LocalDate.now()}: «не сдано» — первое вычисление, зависящее
 * от даты, и тест должен уметь сдвинуть часы, ничего не трогая в Задании.
 *
 * <p>Два условия сервиса спрашивают область Работ через вопрос
 * {@link AssignmentWork}; отвечает на него {@code WorksOfAssignment}
 * из работы {@code submission-review}: Задание с Работой хотя бы по одной
 * Задаче не «не сдано» (ADR-0016) и не удаляется (ADR-0037), вердикт
 * для этого не нужен (ADR-0038). Оба условия — отдельные названные
 * методы, {@link #notSubmitted} и {@link #refuseUnlessNoWork}, чтобы ответ
 * подключался в одно место; что вопрос и ответчик названы, сторожит
 * {@code AssignmentWorkDebtTest}, что ответ работает —
 * {@code AssignmentWorkAnsweredTest}.
 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final AssignmentBatchRepository batches;
    private final StudentService students;
    private final GroupService groups;
    private final ProblemService problems;
    private final TheoryService theory;
    private final CurrentUser currentUser;
    private final Clock clock;

    /**
     * Ответчики на вопрос «есть ли по Заданию Работа» — область Работ
     * ({@code submission-review}); подробности — в {@link AssignmentWork}.
     */
    private final List<AssignmentWork> works;

    public AssignmentService(AssignmentRepository assignments,
                             AssignmentBatchRepository batches,
                             StudentService students,
                             GroupService groups,
                             ProblemService problems,
                             TheoryService theory,
                             CurrentUser currentUser,
                             Clock clock,
                             List<AssignmentWork> works) {
        this.assignments = assignments;
        this.batches = batches;
        this.students = students;
        this.groups = groups;
        this.problems = problems;
        this.theory = theory;
        this.currentUser = currentUser;
        this.clock = clock;
        this.works = works;
    }

    /**
     * Выдаёт Задание одному своему Ученику: Задачи в порядке выбора,
     * повторы сняты, срок обязателен, дата выдачи — сегодня.
     *
     * <p>Чужой Ученик неотличим от несуществующего —
     * {@link ru.locus.student.StudentNotFoundException} из
     * {@link StudentService#student}. Задачи проверяются одним чтением
     * {@link ProblemService#problems}: отсутствующая — отказ до записи.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public AssignmentId issueToStudent(StudentId studentId,
                                       List<ProblemId> problemIds,
                                       LocalDate dueDate,
                                       TheoryScope theoryScope) {
        Issue issue = prepare(problemIds, dueDate, theoryScope);
        Student student = students.student(studentId);
        return assignments.create(owner(), student.id(), null,
                today(), issue.dueDate(), issue.theoryScope(), issue.problems());
    }

    /**
     * Выдаёт Задание каждому Ученику своей Группы одним действием и помечает
     * их общей Раздачей (ADR-0016).
     *
     * <p>Одна транзакция: Раздача с именем Группы на момент выдачи, затем
     * Задание на каждого члена. Пустая Группа — отказ до записи: Раздача
     * без единого Задания — не выдача, а след от неё. Чужая Группа
     * неотличима от несуществующей — {@link ru.locus.student.GroupNotFoundException}
     * из {@link GroupService#group}.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public AssignmentBatchId issueToGroup(GroupId groupId,
                                          List<ProblemId> problemIds,
                                          LocalDate dueDate,
                                          TheoryScope theoryScope) {
        Issue issue = prepare(problemIds, dueDate, theoryScope);
        Group group = groups.group(groupId);
        List<Student> members = groups.members(group.id());
        if (members.isEmpty()) {
            throw new IllegalArgumentException("Группа «" + group.name() + "» пуста: выдавать некому");
        }
        UserId owner = owner();
        LocalDate issuedOn = today();
        AssignmentBatchId batch = batches.create(owner, group.name(), issuedOn);
        for (Student member : members) {
            assignments.create(owner, member.id(), batch,
                    issuedOn, issue.dueDate(), issue.theoryScope(), issue.problems());
        }
        return batch;
    }

    /** Задание вошедшего Учителя с именем Ученика и «не сдано»; чужое или несуществующее — 404. */
    @PreAuthorize("hasRole('TEACHER')")
    public ListedAssignment assignment(AssignmentId id) {
        UserId owner = owner();
        Assignment assignment = existing(owner, id);
        Student student = students.student(assignment.student());
        Set<AssignmentId> withWork = withWork(List.of(assignment.id()));
        return new ListedAssignment(assignment, student.name(), notSubmitted(assignment, today(), withWork));
    }

    /** Раздача вошедшего Учителя; чужая или несуществующая — 404. */
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentBatch batch(AssignmentBatchId id) {
        return existingBatch(owner(), id);
    }

    /** Задания Раздачи по Ученикам с «не сдано» — «кто из Группы не сдал». */
    @PreAuthorize("hasRole('TEACHER')")
    public List<ListedAssignment> ofBatch(AssignmentBatchId id) {
        UserId owner = owner();
        AssignmentBatch batch = existingBatch(owner, id);
        return listed(assignments.findByBatch(owner, batch.id()));
    }

    /**
     * Сводка Заданий по условиям (С7): ближайшие по сроку первыми.
     *
     * <p>Ученик, Раздача и период уходят в запрос; «только несданные»
     * отбирается здесь, после чтения, — условие зависит от часов и от ответа
     * области Работ, которых в SQL нет.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<ListedAssignment> list(AssignmentFilter filter) {
        List<ListedAssignment> found = listed(
                assignments.find(owner(), filter.student(), filter.batch(), filter.from(), filter.to()));
        if (!filter.onlyNotSubmitted()) {
            return found;
        }
        return found.stream().filter(ListedAssignment::notSubmitted).toList();
    }

    /** Раздачи вошедшего Учителя, новые первыми, — для выбора в отборе сводки. */
    @PreAuthorize("hasRole('TEACHER')")
    public List<AssignmentBatch> batches() {
        return batches.findAll(owner());
    }

    /**
     * Состав Задания, названный по-человечески, в порядке выдачи.
     *
     * Задачи — ссылки на библиотеку, не копии: Задание показывает их такими,
     * каковы они сейчас. Заморозка (ADR-0030) гарантирует, что разметка
     * выданной Задачи рукой не меняется, а перестройка дерева двигает её
     * вместе с Темой (ADR-0034) — и показ это отражает.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<FoundProblem> problemsOf(Assignment assignment) {
        return problems.problems(assignment.problems());
    }

    /**
     * Теория к Заданию по охвату — вычисляется при показе из текущей
     * разметки Задач и текущего дерева (ADR-0018, ADR-0037): материал,
     * добавленный после выдачи, виден; списка материалов у Задания нет.
     *
     * <p>Темы — объединение {@code topics()} всех Задач состава, в порядке
     * состава. По каждой Теме — {@link TheoryService#materialsOn}: тот
     * отдаёт свои материалы узла и унаследованные от предков с признаком
     * «свой», и это ровно два охвата. Своего подъёма по предкам здесь
     * не заводится (standards.md, «Данные»: обход один на проект). Вызовов
     * столько, сколько Тем, — единицы; общий метод на набор узлов завёл бы
     * второй порядок обхода.
     *
     * <p>Материал, попавший под охват по нескольким Темам, показывается один
     * раз — первым вхождением, с тем узлом, на котором лежит. {@code NONE} —
     * пустой список без чтения.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<NodeTheory> theoryOf(Assignment assignment) {
        if (assignment.theoryScope() == TheoryScope.NONE) {
            return List.of();
        }
        Set<TaxonomyNodeId> topics = new LinkedHashSet<>();
        for (FoundProblem found : problems.problems(assignment.problems())) {
            topics.addAll(found.problem().topics());
        }
        boolean sectionsToo = assignment.theoryScope() == TheoryScope.TOPICS_AND_SECTIONS;
        Map<TheoryMaterialId, NodeTheory> byMaterial = new LinkedHashMap<>();
        for (TaxonomyNodeId topic : topics) {
            for (NodeTheory material : theory.materialsOn(topic)) {
                if (material.own() || sectionsToo) {
                    byMaterial.putIfAbsent(material.material().id(), material);
                }
            }
        }
        return List.copyOf(byMaterial.values());
    }

    /**
     * Переносит срок своего Задания на любую дату — единственная правка
     * после выдачи (ADR-0037): состав, Ученик и охват не меняются, нужен
     * другой набор — выдаётся новое Задание. Ограничений на дату нет:
     * Учитель записывает и то, что было.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void changeDueDate(AssignmentId id, LocalDate dueDate) {
        UserId owner = owner();
        Assignment assignment = existing(owner, id);
        assignments.changeDueDate(owner, assignment.id(), requireDueDate(dueDate));
    }

    /**
     * Удаляет своё Задание, пока по нему нет Работы (ADR-0037). Состав
     * уходит с ним (каскад в схеме); Ученик и Задачи остаются на месте.
     *
     * Раздача, из которой Задание удалено, остаётся: она — след выдачи
     * Группе такого-то числа, а не список живых Заданий, и удаляется
     * своим действием, {@link #deleteBatch}.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void delete(AssignmentId id) {
        UserId owner = owner();
        Assignment assignment = existing(owner, id);
        refuseUnlessNoWork(List.of(assignment));
        assignments.delete(owner, assignment.id());
    }

    /**
     * Удаляет свою Раздачу вместе со всеми её Заданиями — целиком или никак:
     * если хотя бы одно Задание удержано Работой, отклоняется всё
     * (ADR-0037), и ни одно Задание не исчезает.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void deleteBatch(AssignmentBatchId id) {
        UserId owner = owner();
        AssignmentBatch batch = existingBatch(owner, id);
        refuseUnlessNoWork(assignments.findByBatch(owner, batch.id()));
        assignments.deleteByBatch(owner, batch.id());
        batches.delete(owner, batch.id());
    }

    /**
     * Единственная проверка того, что удаляемые Задания не держит Работа:
     * сюда дописывается каждое новое условие, и искать его потом надо
     * в одном месте.
     *
     * <p>Спрашивается у {@link AssignmentWork} одним вызовом на весь список:
     * Раздача удаляется или отклоняется целиком. Отвечает область Работ —
     * работа {@code submission-review}: Задание с Работой хотя бы по одной
     * Задаче удержано, и Раздача с одним таким Заданием не удаляется вся.
     * Исчезни ответчик — удаление снова прошло бы всегда, и Работы повисли
     * бы без Задания, к которому привязаны (ADR-0037).
     */
    private void refuseUnlessNoWork(List<Assignment> candidates) {
        Set<AssignmentId> held = withWork(candidates.stream().map(Assignment::id).toList());
        if (held.isEmpty()) {
            return;
        }
        String numbers = held.stream().map(id -> String.valueOf(id.value())).sorted()
                .collect(Collectors.joining(", "));
        throw new AssignmentInUseException("По Заданию уже есть Работа (№ " + numbers
                + "): удалить его нельзя");
    }

    /**
     * «Не сдано» — вычисляется, не хранится (инвариант 12 domain-model.md,
     * ADR-0016): срок раньше сегодняшнего дня и Задания нет среди тех,
     * по которым есть Работа. День срока — ещё не просрочка.
     *
     * <p>Вторая половина условия спрашивается у {@link AssignmentWork}
     * заранее и одним вызовом на весь список ({@link #withWork}); сюда
     * приходит готовое множество. Отвечает область Работ — работа
     * {@code submission-review}: Работа считается с момента приёма, без
     * вердикта тоже (ADR-0038). Исчезни ответчик — просроченное Задание
     * осталось бы несданным при любом числе принятых Работ.
     */
    private static boolean notSubmitted(Assignment assignment, LocalDate today, Set<AssignmentId> withWork) {
        return assignment.dueDate().isBefore(today) && !withWork.contains(assignment.id());
    }

    /** Ответ всех ответчиков на вопрос о Работах, объединённый в одно множество. */
    private Set<AssignmentId> withWork(Collection<AssignmentId> ids) {
        Set<AssignmentId> held = new HashSet<>();
        for (AssignmentWork work : works) {
            held.addAll(work.withWork(ids));
        }
        return held;
    }

    /**
     * Список Заданий с именами Учеников и «не сдано»: Ученики читаются одним
     * списком владельца, Работы спрашиваются одним вызовом, часы —
     * один раз на весь список, чтобы граница суток не разрезала его надвое.
     */
    private List<ListedAssignment> listed(List<Assignment> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<StudentId, String> names = students.all().stream()
                .collect(Collectors.toMap(Student::id, Student::name));
        Set<AssignmentId> withWork = withWork(found.stream().map(Assignment::id).toList());
        LocalDate today = today();
        List<ListedAssignment> listed = new ArrayList<>();
        for (Assignment assignment : found) {
            String name = names.get(assignment.student());
            if (name == null) {
                // Составной ключ на (student_id, user_id) такого не допускает;
                // молчать нельзя — Задание без Ученика выглядело бы как чужое.
                throw new IllegalStateException("Задание " + assignment.id().value()
                        + " ссылается на Ученика, которого у владельца нет: " + assignment.student().value());
            }
            listed.add(new ListedAssignment(assignment, name, notSubmitted(assignment, today, withWork)));
        }
        return List.copyOf(listed);
    }

    /** Что общего у обеих выдач: проверенные до записи срок, охват и состав. */
    private record Issue(LocalDate dueDate, TheoryScope theoryScope, List<ProblemId> problems) {
    }

    /**
     * Проверки выдачи — до первой записи и до обращения к адресату: срок
     * обязателен, охват по умолчанию «без теории» (спека, «Теория
     * прикрепляется охватом»), Задач хотя бы одна, повторы сняты
     * с сохранением порядка, все существуют.
     */
    private Issue prepare(List<ProblemId> problemIds, LocalDate dueDate, TheoryScope theoryScope) {
        LocalDate due = requireDueDate(dueDate);
        TheoryScope scope = theoryScope == null ? TheoryScope.NONE : theoryScope;
        List<ProblemId> distinct = problemIds == null
                ? List.of()
                : problemIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("Заданию нужна хотя бы одна Задача");
        }
        problems.problems(distinct);
        return new Issue(due, scope, distinct);
    }

    private static LocalDate requireDueDate(LocalDate dueDate) {
        if (dueDate == null) {
            throw new IllegalArgumentException("Срок Задания обязателен");
        }
        return dueDate;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private UserId owner() {
        return currentUser.id();
    }

    private Assignment existing(UserId owner, AssignmentId id) {
        return assignments.findById(owner, id).orElseThrow(() -> new AssignmentNotFoundException(id));
    }

    private AssignmentBatch existingBatch(UserId owner, AssignmentBatchId id) {
        return batches.findById(owner, id).orElseThrow(() -> new AssignmentBatchNotFoundException(id));
    }
}
