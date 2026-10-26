package ru.locus.warmup;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.assignment.Addressee;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryService;
import ru.locus.problem.FoundProblem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.student.Group;
import ru.locus.student.GroupId;
import ru.locus.student.GroupService;
import ru.locus.student.Student;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Ведение Разминок: заведение, показ, итоги участника вместе с отметками
 * Владения и удаление (ADR-0061).
 *
 * <p>Права проверяются здесь, а не в контроллере (standards.md, «Слои
 * и границы»); каждая операция — только Учителю, включая чтение:
 * у Пользователя без этой роли Разминок нет, и список для него
 * не пуст, а недоступен.
 *
 * <p>Разминки — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027): владелец берётся у {@link CurrentUser} в начале каждой операции
 * и уходит в репозиторий, из запроса он не читается никогда. Чужая Разминка
 * и несуществующая неразличимы — наружу один {@link WarmupNotFoundException}.
 * Ученик и Группа берутся у {@link StudentService} и {@link GroupService}
 * от имени того же вошедшего, так что чужой адресат — несуществующий.
 * Задачи — общая библиотека, их отдаёт {@link ProblemService} без владельца.
 *
 * <p>Отметки Владения пишет не этот сервис, а {@link MasteryService#markByWarmup}:
 * основание отметки — своя Разминка, участник, пара из Задач состава —
 * проверяется в области отметок, как у Работы, и правило «отметка только
 * по основанию» живёт в одном месте. Здесь лишь собирается транзакция
 * «итоги и отметки одним сохранением» ({@link #record}). Зависимость идёт
 * в одну сторону: сервис Владения читает Разминки репозиторием.
 */
@Service
public class WarmupService {

    private final WarmupRepository warmups;
    private final StudentService students;
    private final GroupService groups;
    private final ProblemService problems;
    private final MasteryService mastery;
    private final CurrentUser currentUser;
    private final Clock clock;

    public WarmupService(WarmupRepository warmups,
                         StudentService students,
                         GroupService groups,
                         ProblemService problems,
                         MasteryService mastery,
                         CurrentUser currentUser,
                         Clock clock) {
        this.warmups = warmups;
        this.students = students;
        this.groups = groups;
        this.problems = problems;
        this.mastery = mastery;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Заводит Разминку своему Ученику или своей Группе: Задачи в порядке
     * выбора, повторы сняты, дата обязательна и ничем не ограничена —
     * Учитель записывает и прошедшую Разминку.
     *
     * <p>Все проверки — до записи. Отсутствующая Задача — отказ
     * {@link ProblemService#problems}. Чужой Ученик или чужая Группа
     * неотличимы от несуществующих — 404-исключения {@link StudentService#student}
     * и {@link GroupService#group}. Выбывший Ученик, выбранный прямо, — отказ
     * (ADR-0040): прямой POST не должен обходить форму. У Группы участники —
     * действующие члены на этот момент, выбывшие пропускаются, состав Группы
     * не меняется; пустая Группа и Группа без действующих — отказ, Разминка
     * без участника — не Разминка. Имя Группы запоминается текстом.
     *
     * @throws IllegalArgumentException адресата нет, даты нет, Задач нет
     *         или одной нет в библиотеке, Ученик выбыл, в Группе некому
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public WarmupId hold(Addressee to, LocalDate heldOn, List<ProblemId> problemIds) {
        if (to == null) {
            throw new IllegalArgumentException("адресат один: Ученик либо Группа");
        }
        if (heldOn == null) {
            throw new IllegalArgumentException("Дата Разминки обязательна");
        }
        List<ProblemId> distinct = problemIds == null
                ? List.of()
                : problemIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("Разминке нужна хотя бы одна Задача");
        }
        problems.problems(distinct);
        UserId owner = owner();
        return switch (to) {
            case Addressee.ToStudent(StudentId studentId) -> {
                Student student = students.student(studentId);
                if (student.withdrawn()) {
                    throw new IllegalArgumentException(
                            "Ученик «" + student.name() + "» выбыл: провести ему Разминку нельзя");
                }
                yield warmups.create(owner, heldOn, null, List.of(student.id()), distinct);
            }
            case Addressee.ToGroup(GroupId groupId) -> {
                Group group = groups.group(groupId);
                List<Student> members = groups.members(group.id());
                if (members.isEmpty()) {
                    throw new IllegalArgumentException("Группа «" + group.name() + "» пуста: Разминку проводить некому");
                }
                List<StudentId> active = members.stream()
                        .filter(member -> !member.withdrawn())
                        .map(Student::id)
                        .toList();
                if (active.isEmpty()) {
                    throw new IllegalArgumentException("В группе «" + group.name()
                            + "» не осталось действующих Учеников: Разминку проводить некому");
                }
                yield warmups.create(owner, heldOn, group.name(), active, distinct);
            }
        };
    }

    /** Разминка вошедшего Учителя с адресатом и участниками; чужая или несуществующая — 404. */
    @PreAuthorize("hasRole('TEACHER')")
    public ListedWarmup warmup(WarmupId id) {
        UserId owner = owner();
        return listed(List.of(existing(owner, id))).get(0);
    }

    /**
     * Разминки вошедшего Учителя, новые по дате первыми.
     *
     * @param student участник — только его Разминки; {@code null} — все.
     *                Чужой или несуществующий Ученик — 404, а не пустой
     *                список: пустой выглядел бы правдой о чужом Ученике
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<ListedWarmup> list(StudentId student) {
        UserId owner = owner();
        if (student != null) {
            students.student(student);
        }
        return listed(warmups.find(owner, student));
    }

    /**
     * Состав Разминки, названный по-человечески, в порядке выбора.
     *
     * Задачи — ссылки на библиотеку, не копии: Разминка показывает их
     * такими, каковы они сейчас, как и Задание.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<FoundProblem> problemsOf(Warmup warmup) {
        return problems.problems(warmup.problems());
    }

    /**
     * Итоги Разминки: участник → Задача → итог. Пустой клетки в ответе нет —
     * её нет и в базе; чужая Разминка — пусто.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public Map<StudentId, Map<ProblemId, WarmupOutcome>> outcomesOf(Warmup warmup) {
        return warmups.findOutcomes(owner(), warmup.id());
    }

    /**
     * Итоги одного участника и его отметки Владения — одним сохранением.
     *
     * <p>Одна транзакция, по образцу {@code StudentWorkService.receive}:
     * сначала проверки итогов — Разминка своя, Ученик её участник, каждая
     * Задача из состава и названа не больше раза, — затем
     * {@link MasteryService#markByWarmup} со своими проверками, затем запись
     * итогов. Отказ любого шага откатывает всё: Учитель, получив отказ,
     * не гадает, что из введённого всё же записалось.
     *
     * <p>Итог со значением ставится или меняется, пустая клетка удаляет строку
     * (ADR-0061); Задачи, которых в запросе нет, не трогаются. Итог ничего
     * не выводит: отметки ставятся только те, что Учитель выбрал сам
     * (ADR-0011, ADR-0014).
     *
     * @throws WarmupNotFoundException чужая или несуществующая Разминка
     * @throws IllegalArgumentException Ученик не участник, Задача не из
     *         состава или повторяется, пара не из Задач Разминки
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void record(WarmupId id, StudentId student, List<Outcome> outcomes, List<Mark> marks) {
        UserId owner = owner();
        Warmup warmup = existing(owner, id);
        if (!warmup.hasStudent(student)) {
            throw new IllegalArgumentException("Этот Ученик в Разминке не участвует");
        }
        Set<ProblemId> named = new HashSet<>();
        for (Outcome outcome : outcomes) {
            if (!warmup.problems().contains(outcome.problem())) {
                throw new IllegalArgumentException("Такой Задачи в Разминке нет");
            }
            if (!named.add(outcome.problem())) {
                throw new IllegalArgumentException("Форма итогов повреждена: Задача названа дважды");
            }
        }
        mastery.markByWarmup(warmup.id(), student, marks);
        for (Outcome outcome : outcomes) {
            if (outcome.value() == null) {
                warmups.removeOutcome(owner, warmup.id(), student, outcome.problem());
            } else {
                warmups.putOutcome(owner, warmup.id(), student, outcome.problem(), outcome.value());
            }
        }
    }

    /**
     * Сегодняшняя дата по часам сервера — для поля «дата» формы заведения
     * по умолчанию, как у даты получения Работы: «сегодня» в системе одно,
     * у бина {@link Clock}.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Удаляет свою Разминку в любой момент — вместе с участниками, составом
     * и итогами (каскад в схеме). Ученики и Задачи остаются, отметки
     * Владения тоже: они на паре «Тема × Метод», а не на Разминке (ADR-0061).
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void delete(WarmupId id) {
        UserId owner = owner();
        Warmup warmup = existing(owner, id);
        warmups.delete(owner, warmup.id());
    }

    /**
     * Адресат и участники всего списка — одним чтением Учеников вошедшего,
     * а не чтением на каждую Разминку.
     */
    private List<ListedWarmup> listed(List<Warmup> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<StudentId, Student> byId = students.all().stream()
                .collect(Collectors.toMap(Student::id, Function.identity()));
        List<ListedWarmup> listed = new ArrayList<>();
        for (Warmup warmup : found) {
            List<Student> participants = warmup.students().stream().map(byId::get).toList();
            String addressee = warmup.isForGroup() ? warmup.groupName() : participants.get(0).name();
            listed.add(new ListedWarmup(warmup, addressee, participants));
        }
        return listed;
    }

    private UserId owner() {
        return currentUser.id();
    }

    private Warmup existing(UserId owner, WarmupId id) {
        return warmups.findById(owner, id).orElseThrow(() -> new WarmupNotFoundException(id));
    }
}
