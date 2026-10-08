package ru.locus.lesson;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.lesson.MeetingAdjustment.Move;
import ru.locus.user.UserId;

/**
 * Хранение Поправок Встреч — репозиторий ЛИЧНОГО контура (ADR-0048).
 *
 * <p>У каждого публичного метода есть владелец {@link UserId}, и в каждом SQL
 * стоит {@code user_id} (ADR-0027); исключений класса ADR-0036 нет. Поправить
 * Встречу чужого Занятия нельзя и в обход сервиса: составной ключ
 * {@code (lesson_id, user_id) → lesson(id, user_id)} не примет строку,
 * а чтение и удаление чужого отдают пусто и не трогают ни строки.
 *
 * <p>Пустая Поправка не хранится — её не примет проверка таблицы; сервис
 * вместо записи пустой зовёт {@link #delete}.
 */
@Repository
public class MeetingAdjustmentRepository {

    private static final String COLUMNS = """
            select lesson_id, planned_date, cancelled, moved_date, moved_start, moved_duration_minutes, absent
            from meeting_adjustment
            """;

    private final JdbcClient database;

    public MeetingAdjustmentRepository(JdbcClient database) {
        this.database = database;
    }

    /** Поправка Встречи Занятия владельца на плановую дату; нет её или Занятие чужое — пусто. */
    public Optional<MeetingAdjustment> find(UserId owner, LessonId lesson, LocalDate plannedDate) {
        return database.sql(COLUMNS + "where user_id = ? and lesson_id = ? and planned_date = ?")
                .params(owner.value(), lesson.value(), plannedDate)
                .query(MeetingAdjustmentRepository::adjustment)
                .optional();
    }

    /**
     * Поправки Занятий владельца, у которых плановая <em>или</em> новая дата
     * в отрезке включительно: неделе нужны и строки «перенесена на» со своих
     * дат, и Встречи, перенесённые в неё с чужих.
     */
    public List<MeetingAdjustment> findForLessons(UserId owner, Collection<LessonId> lessons, LocalDate from,
            LocalDate to) {
        if (lessons.isEmpty()) {
            return List.of();
        }
        return database.sql(COLUMNS + """
                        where user_id = :owner and lesson_id in (:lessons)
                          and (planned_date between :from and :to or moved_date between :from and :to)
                        order by lesson_id, planned_date
                        """)
                .param("owner", owner.value())
                .param("lessons", lessons.stream().map(LessonId::value).toList())
                .param("from", from)
                .param("to", to)
                .query(MeetingAdjustmentRepository::adjustment)
                .list();
    }

    /** Все Поправки Занятия владельца по плановой дате. */
    public List<MeetingAdjustment> findByLesson(UserId owner, LessonId lesson) {
        return database.sql(COLUMNS + "where user_id = ? and lesson_id = ? order by planned_date")
                .params(owner.value(), lesson.value())
                .query(MeetingAdjustmentRepository::adjustment)
                .list();
    }

    /**
     * Записывает Поправку Встречи Занятия владельца — заводит или заменяет
     * прежнюю на той же паре. Чужое Занятие база не примет.
     */
    public void save(UserId owner, MeetingAdjustment adjustment) {
        Move move = adjustment.move();
        database.sql("""
                        insert into meeting_adjustment (user_id, lesson_id, planned_date, cancelled,
                                                        moved_date, moved_start, moved_duration_minutes, absent)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (lesson_id, planned_date) do update
                        set cancelled = excluded.cancelled, moved_date = excluded.moved_date,
                            moved_start = excluded.moved_start,
                            moved_duration_minutes = excluded.moved_duration_minutes, absent = excluded.absent
                        where meeting_adjustment.user_id = excluded.user_id
                        """)
                .params(owner.value(), adjustment.lesson().value(), adjustment.plannedDate(), adjustment.cancelled(),
                        move == null ? null : move.date(), move == null ? null : move.start(),
                        move == null ? null : move.durationMinutes(), adjustment.absent())
                .update();
    }

    /** Удаляет Поправку Встречи — Встреча снова идёт по правилу; чужое не трогает. */
    public void delete(UserId owner, LessonId lesson, LocalDate plannedDate) {
        database.sql("delete from meeting_adjustment where user_id = ? and lesson_id = ? and planned_date = ?")
                .params(owner.value(), lesson.value(), plannedDate)
                .update();
    }

    /** Удаляет Поправки Занятия владельца на перечисленные плановые даты; чужое не трогает. */
    public void deleteDates(UserId owner, LessonId lesson, Collection<LocalDate> plannedDates) {
        if (plannedDates.isEmpty()) {
            return;
        }
        database.sql("""
                        delete from meeting_adjustment
                        where user_id = :owner and lesson_id = :lesson and planned_date in (:dates)
                        """)
                .param("owner", owner.value())
                .param("lesson", lesson.value())
                .param("dates", List.copyOf(plannedDates))
                .update();
    }

    /**
     * Передаёт Поправки с плановой даты {@code since} включительно от одной
     * части поделённого Занятия к другой (ADR-0048). Обе части — владельца;
     * чужую новую часть база не примет.
     */
    public void rehome(UserId owner, LessonId from, LessonId to, LocalDate since) {
        database.sql("""
                        update meeting_adjustment set lesson_id = ?
                        where user_id = ? and lesson_id = ? and planned_date >= ?
                        """)
                .params(to.value(), owner.value(), from.value(), since)
                .update();
    }

    private static MeetingAdjustment adjustment(ResultSet rs, int rowNum) throws SQLException {
        LocalDate movedDate = rs.getObject("moved_date", LocalDate.class);
        Move move = movedDate == null ? null
                : new Move(movedDate, rs.getObject("moved_start", LocalTime.class),
                        rs.getInt("moved_duration_minutes"));
        return new MeetingAdjustment(new LessonId(rs.getLong("lesson_id")),
                rs.getObject("planned_date", LocalDate.class), rs.getBoolean("cancelled"), move,
                rs.getBoolean("absent"));
    }
}
