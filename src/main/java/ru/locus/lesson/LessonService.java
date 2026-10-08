package ru.locus.lesson;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
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
    private final MeetingAdjustmentRepository adjustments;
    private final StudentService students;
    private final GroupService groups;
    private final CurrentUser currentUser;
    private final Clock clock;

    public LessonService(LessonRepository lessons, MeetingAdjustmentRepository adjustments, StudentService students,
            GroupService groups, CurrentUser currentUser, Clock clock) {
        this.lessons = lessons;
        this.adjustments = adjustments;
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
        Schedule schedule = schedule(owner(), monday, monday.plusDays(6));
        return Week.of(monday, today, schedule.meetings(), schedule.movedAway());
    }

    /**
     * Сегодняшний день вошедшего Учителя: Встречи в порядке времени
     * и строки о Встречах, перенесённых с сегодняшнего дня.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional(readOnly = true)
    public Week.Day today() {
        LocalDate today = currentDate();
        Schedule schedule = schedule(owner(), today, today);
        return Week.day(today, today, schedule.meetings(), schedule.movedAway());
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

    /**
     * Встреча своего Занятия на плановую дату с её Поправкой. Чужое Занятие
     * и дата, которой правило не даёт, — {@link MeetingNotFoundException}.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional(readOnly = true)
    public MeetingDetails meeting(LessonId id, LocalDate plannedDate) {
        UserId owner = owner();
        ListedLesson listed = existingMeeting(owner, id, plannedDate);
        Meeting meeting = Meetings.meeting(listed, plannedDate,
                adjustments.find(owner, id, plannedDate).orElse(null));
        return new MeetingDetails(listed, meeting, !meeting.date().isAfter(currentDate()));
    }

    /** Переносит Встречу на новое место; перенос снимает отмену, повторный — меняет место (ADR-0048). */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void move(LessonId id, LocalDate plannedDate, MeetingAdjustment.Move move) {
        Objects.requireNonNull(move);
        adjust(id, plannedDate, adjustment -> adjustment.withMove(move));
    }

    /**
     * Отменяет Встречу; отмена снимает перенос. Встреча с неявкой не
     * отменяется: неявка — факт, и тихо стирать его отменой нельзя.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void cancel(LessonId id, LocalDate plannedDate) {
        adjust(id, plannedDate, adjustment -> {
            if (adjustment.absent()) {
                throw new IllegalArgumentException(
                        "Ученик отмечен не пришедшим: сначала отметьте, что он пришёл, потом отменяйте");
            }
            return adjustment.withCancelled(true);
        });
    }

    /** Возвращает Встречу как было по правилу: снимает отмену и перенос, неявку оставляет. */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void restore(LessonId id, LocalDate plannedDate) {
        adjust(id, plannedDate, adjustment -> adjustment.withCancelled(false).withMove(null));
    }

    /**
     * Отмечает, что Ученик не пришёл, или снимает отметку. Ставится только
     * у Встречи Занятия с Учеником, не отменённой и уже наступившей
     * по {@link Clock}; снимается всегда.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void markAbsence(LessonId id, LocalDate plannedDate, boolean absent) {
        UserId owner = owner();
        ListedLesson listed = existingMeeting(owner, id, plannedDate);
        if (absent && listed.lesson().student() == null) {
            throw new IllegalArgumentException("Неявка отмечается только у Встречи с Учеником, не с Группой");
        }
        adjust(owner, listed, plannedDate, adjustment -> {
            if (absent && adjustment.cancelled()) {
                throw new IllegalArgumentException("Встреча отменена: неявку у неё не отмечают");
            }
            return adjustment.withAbsent(absent);
        });
    }

    private void adjust(LessonId id, LocalDate plannedDate, UnaryOperator<MeetingAdjustment> action) {
        UserId owner = owner();
        adjust(owner, existingMeeting(owner, id, plannedDate), plannedDate, action);
    }

    /**
     * Применяет действие к Поправке Встречи и сохраняет её; пустую — удаляет.
     * Неявка бывает только у наступившей Встречи, поэтому и перенос
     * или возврат Встречи с неявкой в будущее отклоняется.
     */
    private void adjust(UserId owner, ListedLesson listed, LocalDate plannedDate,
            UnaryOperator<MeetingAdjustment> action) {
        LessonId id = listed.lesson().id();
        MeetingAdjustment adjusted = action.apply(adjustments.find(owner, id, plannedDate)
                .orElse(MeetingAdjustment.none(id, plannedDate)));
        if (adjusted.isEmpty()) {
            adjustments.delete(owner, id, plannedDate);
            return;
        }
        if (adjusted.absent() && Meetings.meeting(listed, plannedDate, adjusted).date().isAfter(currentDate())) {
            throw new IllegalArgumentException(
                    "Встреча ещё не наступила: неявку отмечают только у прошедшей или сегодняшней");
        }
        adjustments.save(owner, adjusted);
    }

    private ListedLesson existingMeeting(UserId owner, LessonId id, LocalDate plannedDate) {
        return lessons.findById(owner, id)
                .filter(listed -> listed.lesson().timing().occursOn(plannedDate))
                .orElseThrow(() -> new MeetingNotFoundException(id, plannedDate));
    }

    private ListedLesson existing(UserId owner, LessonId id) {
        return lessons.findById(owner, id).orElseThrow(() -> new LessonNotFoundException(id));
    }

    /** Встречи отрезка с Поправками и строки «перенесена на» (ADR-0048). */
    private Schedule schedule(UserId owner, LocalDate from, LocalDate to) {
        List<ListedLesson> candidates = lessons.findCandidates(owner, from, to);
        List<MeetingAdjustment> adjusted = adjustments.findForLessons(owner,
                candidates.stream().map(listed -> listed.lesson().id()).toList(), from, to);
        return new Schedule(Meetings.between(candidates, adjusted, from, to),
                Meetings.movedAway(candidates, adjusted, from, to));
    }

    private record Schedule(List<Meeting> meetings, List<MovedAway> movedAway) {
    }

    private LocalDate currentDate() {
        return LocalDate.now(clock);
    }

    private UserId owner() {
        return currentUser.id();
    }
}
