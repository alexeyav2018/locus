package ru.locus.lesson;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.student.GroupId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Хранение Занятий — репозиторий ЛИЧНОГО контура.
 *
 * <p>У каждого публичного метода есть владелец {@link UserId}, и в каждом SQL
 * стоит {@code user_id = ?} (ADR-0027); исключений класса ADR-0036 нет —
 * библиотека о Занятиях не спрашивает. Чужое Занятие неотличимо
 * от несуществующего: чтение отдаёт пусто, правка не меняет ни строки.
 * Форму стережёт {@code OwnerIsRequiredByLessonsTest}, поведение —
 * {@code LessonRepositoryTest} двумя владельцами.
 *
 * <p>Хранится правило, а не Встречи (ADR-0047): отрезок дат здесь только
 * отбирает Занятия, которые <em>могут</em> дать Встречу, а сами Встречи
 * считает {@code Meetings}.
 */
@Repository
public class LessonRepository {

    private static final String LISTED = """
            select l.id, l.user_id, l.student_id, l.group_id, l.first_date, l.last_date, l.weekly,
                   l.start_time, l.duration_minutes,
                   coalesce(s.name, g.name) as addressee_name,
                   coalesce(s.withdrawn, false) as withdrawn
            from lesson l
            left join student s on s.id = l.student_id and s.user_id = l.user_id
            left join group_ g on g.id = l.group_id and g.user_id = l.user_id
            """;

    private final JdbcClient database;

    public LessonRepository(JdbcClient database) {
        this.database = database;
    }

    /** Заводит Занятие с Учеником владельца; чужого Ученика база не примет. */
    public LessonId create(UserId owner, StudentId student, LessonTiming timing) {
        return insert(owner, student.value(), null, timing);
    }

    /** Заводит Занятие с Группой владельца; чужую Группу база не примет. */
    public LessonId create(UserId owner, GroupId group, LessonTiming timing) {
        return insert(owner, null, group.value(), timing);
    }

    private LessonId insert(UserId owner, Long student, Long group, LessonTiming timing) {
        Long id = database.sql("""
                        insert into lesson (user_id, student_id, group_id, first_date, last_date, weekly,
                                            start_time, duration_minutes)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(owner.value(), student, group, timing.firstDate(), timing.lastDate(), timing.weekly(),
                        timing.start(), timing.durationMinutes())
                .query(Long.class)
                .single();
        return new LessonId(id);
    }

    /** Занятие владельца с адресатом; чужое или несуществующее — пусто. */
    public Optional<ListedLesson> findById(UserId owner, LessonId id) {
        return database.sql(LISTED + "where l.user_id = ? and l.id = ?")
                .params(owner.value(), id.value())
                .query(LessonRepository::listed)
                .optional();
    }

    /**
     * Занятия владельца, которые могут дать Встречу в отрезке дат включительно:
     * разовое — если его дата в отрезке, еженедельное — если отрезок
     * пересекается с его действием. Порядок — по времени начала.
     */
    public List<ListedLesson> findCandidates(UserId owner, LocalDate from, LocalDate to) {
        return database.sql(LISTED + """
                        where l.user_id = ?
                          and l.first_date <= ?
                          and ((l.weekly and (l.last_date is null or l.last_date >= ?))
                               or (not l.weekly and l.first_date >= ?))
                        order by l.start_time, l.id
                        """)
                .params(owner.value(), to, from, from)
                .query(LessonRepository::listed)
                .list();
    }

    /** Заменяет правило Занятия владельца; адресат не меняется, чужое не трогается. */
    public void update(UserId owner, LessonId id, LessonTiming timing) {
        database.sql("""
                        update lesson
                        set first_date = ?, last_date = ?, weekly = ?, start_time = ?, duration_minutes = ?
                        where user_id = ? and id = ?
                        """)
                .params(timing.firstDate(), timing.lastDate(), timing.weekly(), timing.start(), timing.durationMinutes(),
                        owner.value(), id.value())
                .update();
    }

    /** Удаляет Занятие владельца; чужое не трогает. */
    public void delete(UserId owner, LessonId id) {
        database.sql("delete from lesson where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    /** Сколько Занятий владельца назначено Ученику — ответ на {@code StudentUsage}. */
    public int countByStudent(UserId owner, StudentId student) {
        return database.sql("select count(*) from lesson where user_id = ? and student_id = ?")
                .params(owner.value(), student.value())
                .query(Integer.class)
                .single();
    }

    private static ListedLesson listed(ResultSet rs, int rowNum) throws SQLException {
        Long student = rs.getObject("student_id", Long.class);
        Long group = rs.getObject("group_id", Long.class);
        LessonTiming timing = new LessonTiming(rs.getObject("first_date", LocalDate.class),
                rs.getObject("last_date", LocalDate.class), rs.getBoolean("weekly"),
                rs.getObject("start_time", LocalTime.class), rs.getInt("duration_minutes"));
        Lesson lesson = new Lesson(new LessonId(rs.getLong("id")), new UserId(rs.getLong("user_id")),
                student == null ? null : new StudentId(student), group == null ? null : new GroupId(group), timing);
        return new ListedLesson(lesson, rs.getString("addressee_name"), rs.getBoolean("withdrawn"));
    }
}
