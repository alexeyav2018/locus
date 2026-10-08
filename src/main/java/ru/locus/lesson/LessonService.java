package ru.locus.lesson;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Расписание Учителя: неделя Встреч и Встречи сегодняшнего дня.
 *
 * <p>Занятия — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027): владельца сервис берёт у {@link CurrentUser} в начале каждой
 * операции и передаёт в репозиторий, у которого без владельца нет ни одного
 * метода. Доступ — только роли Учителя, и чтение тоже: у Пользователя без
 * этой роли расписания нет.
 *
 * <p>Хранится правило, Встречи вычисляет {@link Meetings} (ADR-0047).
 * «Сегодня» берётся у бина {@link Clock} ({@code TimeConfiguration}),
 * а не у {@code LocalDate.now()} — тест сдвигает часы.
 */
@Service
public class LessonService {

    private final LessonRepository lessons;
    private final CurrentUser currentUser;
    private final Clock clock;

    public LessonService(LessonRepository lessons, CurrentUser currentUser, Clock clock) {
        this.lessons = lessons;
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
