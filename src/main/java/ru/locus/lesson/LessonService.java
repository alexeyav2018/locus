package ru.locus.lesson;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.student.GroupId;
import ru.locus.student.GroupService;
import ru.locus.student.Student;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Расписание Учителя: неделя Встреч, Встречи сегодняшнего дня и ведение
 * Занятий — заведение, правка с даты и удаление.
 *
 * <p>Занятия — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027): владельца сервис берёт у {@link CurrentUser} в начале каждой
 * операции и передаёт в репозиторий, у которого без владельца нет ни одного
 * метода. Доступ — только роли Учителя, и чтение тоже: у Пользователя без
 * этой роли расписания нет.
 *
 * <p>Адресат берётся у {@link StudentService} и {@link GroupService} от имени
 * того же вошедшего, так что чужой Ученик или чужая Группа для Занятия —
 * несуществующие; то же держат составные ключи таблицы. Чужое Занятие
 * неотличимо от несуществующего — {@link LessonNotFoundException}.
 *
 * <p>Хранится правило, Встречи вычисляет {@link Meetings} (ADR-0047).
 * «Сегодня» берётся у бина {@link Clock} ({@code TimeConfiguration}),
 * а не у {@code LocalDate.now()} — тест сдвигает часы.
 */
@Service
public class LessonService {

    private final LessonRepository lessons;
    private final StudentService students;
    private final GroupService groups;
    private final CurrentUser currentUser;
    private final Clock clock;

    public LessonService(LessonRepository lessons, StudentService students, GroupService groups,
            CurrentUser currentUser, Clock clock) {
        this.lessons = lessons;
        this.students = students;
        this.groups = groups;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Неделя вошедшего Учителя, в которую попадает дата; без даты — неделя,
     * содержащая сегодняшний день.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional(readOnly = true)
    public Week week(LocalDate date) {
        LocalDate today = currentDate();
        LocalDate monday = Week.mondayOf(date == null ? today : date);
        return Week.of(monday, today, meetings(owner(), monday, monday.plusDays(6)));
    }

    /** Встречи вошедшего Учителя сегодня, в порядке времени. */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional(readOnly = true)
    public List<Meeting> today() {
        LocalDate today = currentDate();
        return meetings(owner(), today, today);
    }

    /** Занятие вошедшего Учителя с адресатом; чужое или несуществующее — 404. */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional(readOnly = true)
    public ListedLesson lesson(LessonId id) {
        return existing(owner(), id);
    }

    /**
     * Заводит Занятие со своим Учеником или своей Группой — ровно с одним
     * из двух. Правило во времени проверено ещё при сборке {@link LessonTiming}.
     *
     * <p>Выбывшему Ученику Занятие не назначается независимо от того,
     * показан ли он в форме (ADR-0040): прямой POST не должен обходить
     * правило. У Группы такой проверки нет — Занятие Группы идёт с Группой,
     * а не с её членами.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public LessonId create(StudentId studentId, GroupId groupId, LessonTiming timing) {
        if ((studentId == null) == (groupId == null)) {
            throw new IllegalArgumentException("Адресат Занятия — ровно один: Ученик или Группа");
        }
        Objects.requireNonNull(timing);
        UserId owner = owner();
        if (groupId != null) {
            return lessons.create(owner, groups.group(groupId).id(), timing);
        }
        Student student = students.student(studentId);
        if (student.withdrawn()) {
            throw new IllegalArgumentException(
                    "Ученик «" + student.name() + "» выбыл: назначить ему Занятие нельзя");
        }
        return lessons.create(owner, student.id(), timing);
    }

    /**
     * Правит своё Занятие и отдаёт Занятие, которое действует после правки:
     * то же самое или новое, если прежнее пришлось поделить (ADR-0047).
     *
     * <ol>
     *     <li>Разовое правится на месте; {@code effectiveFrom} не читается.</li>
     *     <li>Еженедельное, у которого меняется только последняя дата,
     *         правится на месте: прошлого она не трогает.</li>
     *     <li>Иначе новая первая дата — ближайший новый день недели не раньше
     *         {@code effectiveFrom}. Была Встреча раньше {@code effectiveFrom}
     *         (первая встреча раньше неё) — прежнее Занятие заканчивается
     *         накануне, а новое с тем же адресатом начинается с новой первой
     *         даты. Не было — Занятие правится на месте целиком.</li>
     * </ol>
     *
     * Деление и правка — одна транзакция: прежнее не закончится без
     * продолжения.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public LessonId change(LessonId id, LessonChange change, LocalDate effectiveFrom) {
        UserId owner = owner();
        Lesson lesson = existing(owner, id).lesson();
        LessonTiming before = lesson.timing();
        if (!before.weekly()) {
            lessons.update(owner, lesson.id(),
                    LessonTiming.of(change.date(), null, false, change.start(), change.durationMinutes()));
            return lesson.id();
        }
        if (change.dayOfWeek() == null) {
            throw new IllegalArgumentException("День недели обязателен");
        }
        if (change.dayOfWeek() == before.dayOfWeek() && before.start().equals(change.start())
                && Objects.equals(before.durationMinutes(), change.durationMinutes())) {
            lessons.update(owner, lesson.id(), LessonTiming.weekly(before.firstDate(), change.lastDate(),
                    before.start(), before.durationMinutes()));
            return lesson.id();
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Укажите дату, с которой правка действует");
        }
        LocalDate firstDate = effectiveFrom.with(TemporalAdjusters.nextOrSame(change.dayOfWeek()));
        LessonTiming after = LessonTiming.of(firstDate, change.lastDate(), true, change.start(),
                change.durationMinutes());
        if (!before.firstDate().isBefore(effectiveFrom)) {
            lessons.update(owner, lesson.id(), after);
            return lesson.id();
        }
        LocalDate dayBefore = effectiveFrom.minusDays(1);
        LocalDate lastDate = before.lastDate() != null && before.lastDate().isBefore(dayBefore)
                ? before.lastDate() : dayBefore;
        lessons.update(owner, lesson.id(),
                LessonTiming.weekly(before.firstDate(), lastDate, before.start(), before.durationMinutes()));
        return lesson.student() != null
                ? lessons.create(owner, lesson.student(), after)
                : lessons.create(owner, lesson.group(), after);
    }

    /** Удаляет своё Занятие в любой момент вместе со всеми его Встречами; адресат остаётся. */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void delete(LessonId id) {
        UserId owner = owner();
        lessons.delete(owner, existing(owner, id).lesson().id());
    }

    private ListedLesson existing(UserId owner, LessonId id) {
        return lessons.findById(owner, id).orElseThrow(() -> new LessonNotFoundException(id));
    }

    private List<Meeting> meetings(UserId owner, LocalDate from, LocalDate to) {
        return Meetings.between(lessons.findCandidates(owner, from, to), from, to);
    }

    private LocalDate currentDate() {
        return LocalDate.now(clock);
    }

    private UserId owner() {
        return currentUser.id();
    }
}
