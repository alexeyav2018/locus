package ru.locus.warmup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.UserId;
import ru.locus.work.ProblemPair;
import ru.locus.work.Solved;

/**
 * Хранение Разминок, их участников, состава и Итогов Разминки — область
 * ЛИЧНОГО контура (ADR-0061).
 *
 * Как у {@link ru.locus.assignment.AssignmentRepository}, у каждого метода
 * есть параметр владельца {@link UserId}, и в каждом SQL стоит
 * {@code user_id = ?} (ADR-0027): чужая Разминка для вызывающего неотличима
 * от несуществующей — чтение отдаёт пусто, правка и удаление не меняют
 * ни одной строки. Единственный метод без владельца —
 * {@link #countByProblem}, и он назван поимённо
 * в {@code OwnerIsRequiredByWarmupsTest} с причиной (ADR-0036).
 *
 * Владелец записан в каждой строке ради составных ключей: участник
 * ссылается на {@code student(id, user_id)}, итог — на участника этой
 * Разминки {@code warmup_student(warmup_id, student_id, user_id)} и на
 * Задачу её состава. Разминка с чужим Учеником и итог не участнику
 * не вставляются — падают на ключах. Сервис отказывает раньше и внятно;
 * здесь ограничение — последний рубеж, а не способ проверки.
 *
 * Пустая клетка — отсутствие строки итога: {@link #removeOutcome} удаляет
 * строку, а не пишет «пусто» (ADR-0061).
 *
 * Правил и проверок прав репозиторий не содержит: их ставит
 * {@code WarmupService}.
 */
@Repository
public class WarmupRepository {

    private final JdbcClient database;

    public WarmupRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Заводит Разминку у владельца вместе с участниками и составом
     * и возвращает её идентификатор.
     *
     * Задачи уходят одной вставкой, {@code position} — порядок в переданном
     * списке. Повторов быть не должно — их снимает сервис; здесь повтор
     * уронит первичный ключ, а не даст вторую строку. Чужой Ученик
     * в участниках падает на {@code fk_warmup_student_student}. Атомарность
     * трёх вставок — дело транзакции сервиса.
     *
     * @param groupName имя Группы на момент заведения; {@code null} —
     *                  Разминка Ученику
     */
    public WarmupId create(UserId owner,
                           LocalDate heldOn,
                           String groupName,
                           List<StudentId> students,
                           List<ProblemId> problems) {
        Long id = database.sql("""
                        insert into warmup (user_id, held_on, group_name)
                        values (?, ?, ?)
                        returning id
                        """)
                .params(owner.value(), heldOn, groupName)
                .query(Long.class)
                .single();
        if (!students.isEmpty()) {
            List<Object> values = new ArrayList<>();
            for (StudentId student : students) {
                values.add(id);
                values.add(student.value());
                values.add(owner.value());
            }
            database.sql("insert into warmup_student (warmup_id, student_id, user_id) values "
                            + String.join(", ", Collections.nCopies(students.size(), "(?, ?, ?)")))
                    .params(values)
                    .update();
        }
        if (!problems.isEmpty()) {
            List<Object> values = new ArrayList<>();
            for (int position = 0; position < problems.size(); position++) {
                values.add(id);
                values.add(problems.get(position).value());
                values.add(owner.value());
                values.add(position);
            }
            database.sql("insert into warmup_problem (warmup_id, problem_id, user_id, position) values "
                            + String.join(", ", Collections.nCopies(problems.size(), "(?, ?, ?, ?)")))
                    .params(values)
                    .update();
        }
        return new WarmupId(id);
    }

    /**
     * Разминка владельца с участниками по алфавиту имён и Задачами
     * в порядке выбора; чужая или несуществующая — пусто.
     */
    public Optional<Warmup> findById(UserId owner, WarmupId id) {
        return database.sql("select id, user_id, held_on, group_name from warmup where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .query(WarmupRepository::row)
                .optional()
                .map(row -> withParts(owner, List.of(row)).get(0));
    }

    /**
     * Разминки владельца, новые по дате первыми; с одной датой — позже
     * заведённая первой, чтобы не меняться местами от чтения к чтению.
     *
     * @param student участник или {@code null} — все Разминки владельца
     */
    public List<Warmup> find(UserId owner, StudentId student) {
        StringBuilder sql = new StringBuilder("select w.id, w.user_id, w.held_on, w.group_name from warmup w"
                + " where w.user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        if (student != null) {
            sql.append(" and exists (select 1 from warmup_student ws"
                    + " where ws.warmup_id = w.id and ws.user_id = w.user_id and ws.student_id = ?)");
            params.add(student.value());
        }
        sql.append(" order by w.held_on desc, w.id desc");
        List<Row> rows = database.sql(sql.toString())
                .params(params)
                .query(WarmupRepository::row)
                .list();
        return withParts(owner, rows);
    }

    /**
     * Удаляет Разминку владельца; чужую не трогает.
     *
     * Участники, состав и итоги уходят каскадом: они часть записи. Ученики
     * и Задачи остаются — на них ключи без каскада. Отметки Владения
     * остаются тоже: они на паре «Тема × Метод», а не на Разминке (ADR-0061).
     */
    public void delete(UserId owner, WarmupId id) {
        database.sql("delete from warmup where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    /**
     * Итоги Разминки владельца: участник → Задача → итог. Пустых клеток
     * в ответе нет — их нет и в базе; участник без единого итога
     * в ответ не попадает. Чужая или несуществующая Разминка — пусто.
     */
    public Map<StudentId, Map<ProblemId, WarmupOutcome>> findOutcomes(UserId owner, WarmupId id) {
        Map<StudentId, Map<ProblemId, WarmupOutcome>> outcomes = new LinkedHashMap<>();
        database.sql("""
                        select student_id, problem_id, outcome
                        from warmup_outcome
                        where user_id = ? and warmup_id = ?
                        order by student_id, problem_id
                        """)
                .params(owner.value(), id.value())
                .query((rs, rowNum) -> new OutcomeRow(
                        new StudentId(rs.getLong("student_id")),
                        new ProblemId(rs.getLong("problem_id")),
                        WarmupOutcome.valueOf(rs.getString("outcome"))))
                .list()
                .forEach(row -> outcomes.computeIfAbsent(row.student(), key -> new LinkedHashMap<>())
                        .put(row.problem(), row.outcome()));
        return outcomes;
    }

    /**
     * Ставит или меняет итог участника по Задаче состава.
     *
     * Итог не участнику, не по Задаче состава и в чужой Разминке
     * не вставляется — падает на ключах {@code fk_warmup_outcome_student}
     * и {@code fk_warmup_outcome_problem}. Чужой итог на той же паре
     * существовать не может: пара ключа лежит в Разминке одного владельца.
     */
    public void putOutcome(UserId owner, WarmupId id, StudentId student, ProblemId problem, WarmupOutcome outcome) {
        database.sql("""
                        insert into warmup_outcome (warmup_id, student_id, problem_id, user_id, outcome)
                        values (?, ?, ?, ?, ?)
                        on conflict (warmup_id, student_id, problem_id)
                        do update set outcome = excluded.outcome
                        where warmup_outcome.user_id = excluded.user_id
                        """)
                .params(id.value(), student.value(), problem.value(), owner.value(), outcome.name())
                .update();
    }

    /** Возвращает клетку в пустоту — удаляет строку итога; чужую не трогает. */
    public void removeOutcome(UserId owner, WarmupId id, StudentId student, ProblemId problem) {
        database.sql("""
                        delete from warmup_outcome
                        where user_id = ? and warmup_id = ? and student_id = ? and problem_id = ?
                        """)
                .params(owner.value(), id.value(), student.value(), problem.value())
                .update();
    }

    /** В скольких Разминках владельца участвует Ученик; чужой Ученик — ноль. */
    public int countByStudent(UserId owner, StudentId student) {
        return database.sql("select count(*) from warmup_student where user_id = ? and student_id = ?")
                .params(owner.value(), student.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Справка по парам «Тема × Метод» из Итогов Разминки Ученика владельца:
     * сколько всего итогов по Задачам с этой парой и сколько из них
     * «справился». Пустые клетки не считаются — их нет в базе. Пары без
     * единого итога в ответ не попадают; чужой Ученик — пусто.
     */
    public Map<ProblemPair, Solved> countOutcomesByPairs(UserId owner, StudentId student) {
        Map<ProblemPair, Solved> solved = new LinkedHashMap<>();
        database.sql("""
                        select pt.topic_id, pm.solution_method_id,
                               count(*) filter (where o.outcome = ?) as succeeded,
                               count(*) as judged
                        from warmup_outcome o
                        join problem_topic pt on pt.problem_id = o.problem_id
                        join problem_solution_method pm on pm.problem_id = o.problem_id
                        where o.user_id = ? and o.student_id = ?
                        group by pt.topic_id, pm.solution_method_id
                        order by pt.topic_id, pm.solution_method_id
                        """)
                .params(WarmupOutcome.SUCCEEDED.name(), owner.value(), student.value())
                .query((rs, rowNum) -> Map.entry(
                        new ProblemPair(new TaxonomyNodeId(rs.getLong("topic_id")),
                                new SolutionMethodId(rs.getLong("solution_method_id"))),
                        new Solved(rs.getInt("succeeded"), rs.getInt("judged"))))
                .list()
                .forEach(entry -> solved.put(entry.getKey(), entry.getValue()));
        return solved;
    }

    /**
     * Сколько Разминок — у ВСЕХ владельцев — включают Задачу.
     *
     * Метод без владельца, и он здесь законно, как
     * {@link ru.locus.assignment.AssignmentRepository#countByProblem}:
     * Задача — общая библиотека, и спрашивает о ней Администратор, у которого
     * личного контура нет (ADR-0030). Наружу уходит только число: ни Ученика,
     * ни Учителя, ни самой Разминки. Класс исключений из ADR-0027, описанный
     * в ADR-0036; метод перечислен поимённо в {@code OwnerIsRequiredByWarmupsTest}.
     */
    public int countByProblem(ProblemId problem) {
        return database.sql("select count(*) from warmup_problem where problem_id = ?")
                .param(problem.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Участники и состав всего списка — двумя запросами на весь список,
     * а не парой на каждую Разминку. Порядок Разминок сохраняется;
     * участники — по алфавиту имён, Задачи — по {@code position}.
     */
    private List<Warmup> withParts(UserId owner, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        rows.forEach(row -> params.add(row.id().value()));
        String among = String.join(", ", Collections.nCopies(rows.size(), "?"));

        Map<Long, List<StudentId>> students = new LinkedHashMap<>();
        database.sql("""
                        select ws.warmup_id, ws.student_id
                        from warmup_student ws
                        join student s on s.id = ws.student_id and s.user_id = ws.user_id
                        where ws.user_id = ? and ws.warmup_id in (%s)
                        order by ws.warmup_id, lower(s.name), s.id
                        """.formatted(among))
                .params(params)
                .query((rs, rowNum) -> Map.entry(rs.getLong("warmup_id"), new StudentId(rs.getLong("student_id"))))
                .list()
                .forEach(entry -> students.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));

        Map<Long, List<ProblemId>> problems = new LinkedHashMap<>();
        database.sql("""
                        select warmup_id, problem_id
                        from warmup_problem
                        where user_id = ? and warmup_id in (%s)
                        order by warmup_id, position
                        """.formatted(among))
                .params(params)
                .query((rs, rowNum) -> Map.entry(rs.getLong("warmup_id"), new ProblemId(rs.getLong("problem_id"))))
                .list()
                .forEach(entry -> problems.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));

        return rows.stream()
                .map(row -> new Warmup(row.id(), row.owner(), row.heldOn(), row.groupName(),
                        students.getOrDefault(row.id().value(), List.of()),
                        problems.getOrDefault(row.id().value(), List.of())))
                .toList();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                new WarmupId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                rs.getObject("held_on", LocalDate.class),
                rs.getString("group_name"));
    }

    /** Строка {@code warmup} без участников и состава — они дочитываются отдельно. */
    private record Row(WarmupId id, UserId owner, LocalDate heldOn, String groupName) {
    }

    /** Строка {@code warmup_outcome} — до раскладки по участникам. */
    private record OutcomeRow(StudentId student, ProblemId problem, WarmupOutcome outcome) {
    }
}
