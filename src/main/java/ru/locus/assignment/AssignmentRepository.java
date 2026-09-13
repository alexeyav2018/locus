package ru.locus.assignment;

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
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Хранение Заданий и их состава — вторая область ЛИЧНОГО контура
 * (ADR-0016).
 *
 * Как у {@link ru.locus.student.StudentRepository}, у каждого метода есть
 * параметр владельца {@link UserId}, и в каждом SQL стоит {@code user_id = ?}
 * (ADR-0027): чужое Задание для вызывающего неотличимо от несуществующего —
 * чтение отдаёт пусто, правка и удаление не меняют ни одной строки.
 * Единственный метод без владельца — {@link #countByProblem}, и он назван
 * поимённо в {@code OwnerIsRequiredByAssignmentsTest} с причиной
 * (ADR-0036); поведение остальных проверяет
 * {@code AssignmentRepositoryTest} двумя владельцами на каждом методе.
 *
 * Состав лежит в {@code assignment_problem}, и владелец записан в каждой
 * его строке — не ради удобства, а ради составных ключей: строка Задания
 * ссылается на {@code student(id, user_id)} и
 * {@code assignment_batch(id, user_id)}, строка состава — на
 * {@code assignment(id, user_id)}. Задание чужому Ученику или в чужой
 * Раздаче не может существовать — ему не на что сослаться, и база его
 * не примет. Сервис отказывает раньше и с внятным сообщением; здесь
 * ограничение — последний рубеж, а не способ проверки (design.md, «Схема»).
 *
 * Колонки «сдано» в таблице нет: «не сдано» — выражение «срок прошёл
 * и Работы нет», вычисляемое сервисом при показе (инвариант 12
 * domain-model.md, ADR-0016). Потому здесь нет и метода «несданные»:
 * репозиторий отдаёт Задания с сроком, а судит о них сервис.
 *
 * Правил и проверок прав репозиторий не содержит: их ставит
 * {@code AssignmentService}.
 */
@Repository
public class AssignmentRepository {

    private static final String COLUMNS =
            "a.id, a.user_id, a.student_id, a.assignment_batch_id, a.issued_on, a.due_date, a.theory_scope";

    private final JdbcClient database;

    public AssignmentRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Заводит Задание у владельца вместе с составом и возвращает
     * его идентификатор.
     *
     * Состав уходит одной вставкой со всеми строками: {@code position} —
     * порядок в переданном списке, и это тот порядок, в котором учитель
     * выбрал Задачи. Повторов в списке быть не должно — их снимает сервис,
     * а запись {@link Assignment} отказывает на них; здесь повтор уронит
     * первичный ключ {@code pk_assignment_problem}, а не даст вторую строку.
     * Атомарность двух вставок — дело транзакции сервиса.
     *
     * @param batch Раздача, если Задание выдано Группе; {@code null} —
     *              выдано лично
     */
    public AssignmentId create(UserId owner,
                               StudentId student,
                               AssignmentBatchId batch,
                               LocalDate issuedOn,
                               LocalDate dueDate,
                               TheoryScope theoryScope,
                               List<ProblemId> problems) {
        Long id = database.sql("""
                        insert into assignment (user_id, student_id, assignment_batch_id, issued_on, due_date, theory_scope)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(owner.value(), student.value(), batch == null ? null : batch.value(),
                        issuedOn, dueDate, theoryScope.name())
                .query(Long.class)
                .single();
        if (!problems.isEmpty()) {
            List<Object> values = new ArrayList<>();
            for (int position = 0; position < problems.size(); position++) {
                values.add(id);
                values.add(problems.get(position).value());
                values.add(owner.value());
                values.add(position);
            }
            database.sql("insert into assignment_problem (assignment_id, problem_id, user_id, position) values "
                            + String.join(", ", Collections.nCopies(problems.size(), "(?, ?, ?, ?)")))
                    .params(values)
                    .update();
        }
        return new AssignmentId(id);
    }

    /** Задание владельца с составом в порядке выдачи; чужое или несуществующее — пусто. */
    public Optional<Assignment> findById(UserId owner, AssignmentId id) {
        return database.sql("select " + COLUMNS + " from assignment a where a.user_id = ? and a.id = ?")
                .params(owner.value(), id.value())
                .query(AssignmentRepository::row)
                .optional()
                .map(row -> row.withProblems(problemsOf(owner, row.id())));
    }

    /**
     * Задания владельца по условиям, ближайшие по сроку первыми; Задания
     * с одним сроком — по идентификатору, чтобы не меняться местами
     * от чтения к чтению.
     *
     * Каждое условие необязательно; заданные соединяются по «и». Период —
     * по сроку, обе границы включительно. Без условий — все Задания
     * владельца. Состав всего списка дочитывается одним вторым запросом,
     * а не запросом на каждое Задание: сводка без условий — самый длинный
     * список области.
     *
     * @param student Ученик или {@code null} — любой
     * @param batch   Раздача или {@code null} — любая, включая выданные лично
     * @param from    начало периода срока или {@code null}
     * @param to      конец периода срока или {@code null}
     */
    public List<Assignment> find(UserId owner, StudentId student, AssignmentBatchId batch, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from assignment a where a.user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        if (student != null) {
            sql.append(" and a.student_id = ?");
            params.add(student.value());
        }
        if (batch != null) {
            sql.append(" and a.assignment_batch_id = ?");
            params.add(batch.value());
        }
        if (from != null) {
            sql.append(" and a.due_date >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" and a.due_date <= ?");
            params.add(to);
        }
        sql.append(" order by a.due_date, a.id");
        List<Row> rows = database.sql(sql.toString())
                .params(params)
                .query(AssignmentRepository::row)
                .list();
        return withProblems(owner, rows);
    }

    /**
     * Задания Раздачи владельца по Ученикам — по алфавиту имён, как состав
     * Группы, — «кто из Группы не сдал» читается сверху вниз; чужая
     * или несуществующая Раздача — пустой список.
     */
    public List<Assignment> findByBatch(UserId owner, AssignmentBatchId batch) {
        List<Row> rows = database.sql("""
                        select %s
                        from assignment a
                        join student s on s.id = a.student_id and s.user_id = a.user_id
                        where a.user_id = ? and a.assignment_batch_id = ?
                        order by lower(s.name), s.id, a.id
                        """.formatted(COLUMNS))
                .params(owner.value(), batch.value())
                .query(AssignmentRepository::row)
                .list();
        return withProblems(owner, rows);
    }

    /**
     * Переносит срок Задания владельца; чужое не трогает.
     *
     * Единственная правка Задания после выдачи (ADR-0037): состав, Ученик
     * и охват не меняются, и методов для этого здесь нет.
     */
    public void changeDueDate(UserId owner, AssignmentId id, LocalDate dueDate) {
        database.sql("update assignment set due_date = ? where user_id = ? and id = ?")
                .params(dueDate, owner.value(), id.value())
                .update();
    }

    /**
     * Удаляет Задание владельца; чужое не трогает.
     *
     * Состав уходит каскадом {@code fk_assignment_problem_assignment}:
     * он часть записи. Ученик и Задачи остаются — на них ключи без каскада,
     * и удаление Задания их не касается.
     */
    public void delete(UserId owner, AssignmentId id) {
        database.sql("delete from assignment where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    /**
     * Удаляет все Задания Раздачи владельца; чужую Раздачу не трогает.
     *
     * Саму Раздачу не удаляет: это делает
     * {@link AssignmentBatchRepository#delete} следом, в той же транзакции
     * сервиса (ADR-0037). Раздача удаляется целиком или никак, но решает
     * это сервис, спросив область Работ по каждому Заданию.
     */
    public void deleteByBatch(UserId owner, AssignmentBatchId batch) {
        database.sql("delete from assignment where user_id = ? and assignment_batch_id = ?")
                .params(owner.value(), batch.value())
                .update();
    }

    /** Сколько Заданий выдано Ученику владельца; чужой Ученик — ноль. */
    public int countByStudent(UserId owner, StudentId student) {
        return database.sql("select count(*) from assignment where user_id = ? and student_id = ?")
                .params(owner.value(), student.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Сколько Заданий — у ВСЕХ владельцев — включают Задачу.
     *
     * Это метод без владельца, и он здесь законно: Задача — общая
     * библиотека, и спрашивает о ней Администратор, у которого личного
     * контура нет вовсе (ADR-0030, заморозка после первого использования).
     * Ответить можно, только пересчитав Задания всех Учителей; фильтр
     * по владельцу не забыт, а невозможен — подставить некого. Наружу
     * уходит только число: ни Ученика, ни Учителя, ни самого Задания.
     * Это вопрос библиотеки к личному контуру о своей сущности — класс
     * исключений из ADR-0027, описанный в ADR-0036; метод перечислен
     * поимённо в {@code OwnerIsRequiredByAssignmentsTest}.
     */
    public int countByProblem(ProblemId problem) {
        return database.sql("select count(*) from assignment_problem where problem_id = ?")
                .param(problem.value())
                .query(Integer.class)
                .single();
    }

    private List<ProblemId> problemsOf(UserId owner, AssignmentId assignment) {
        return database.sql("""
                        select problem_id
                        from assignment_problem
                        where user_id = ? and assignment_id = ?
                        order by position
                        """)
                .params(owner.value(), assignment.value())
                .query(Long.class)
                .list()
                .stream()
                .map(ProblemId::new)
                .toList();
    }

    /** Состав всего списка одним запросом; порядок Заданий сохраняется, порядок Задач — по {@code position}. */
    private List<Assignment> withProblems(UserId owner, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        rows.forEach(row -> params.add(row.id().value()));
        Map<Long, List<ProblemId>> problems = new LinkedHashMap<>();
        database.sql("""
                        select assignment_id, problem_id
                        from assignment_problem
                        where user_id = ? and assignment_id in (%s)
                        order by assignment_id, position
                        """.formatted(String.join(", ", Collections.nCopies(rows.size(), "?"))))
                .params(params)
                .query((rs, rowNum) -> Map.entry(rs.getLong("assignment_id"), new ProblemId(rs.getLong("problem_id"))))
                .list()
                .forEach(entry -> problems.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        return rows.stream()
                .map(row -> row.withProblems(problems.getOrDefault(row.id().value(), List.of())))
                .toList();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        Long batch = rs.getObject("assignment_batch_id", Long.class);
        return new Row(
                new AssignmentId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                new StudentId(rs.getLong("student_id")),
                batch == null ? null : new AssignmentBatchId(batch),
                rs.getObject("issued_on", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                TheoryScope.valueOf(rs.getString("theory_scope")));
    }

    /** Строка {@code assignment} без состава — состав дочитывается отдельно. */
    private record Row(AssignmentId id,
                       UserId owner,
                       StudentId student,
                       AssignmentBatchId batch,
                       LocalDate issuedOn,
                       LocalDate dueDate,
                       TheoryScope theoryScope) {

        Assignment withProblems(List<ProblemId> problems) {
            return new Assignment(id, owner, student, batch, issuedOn, dueDate, theoryScope, problems);
        }
    }
}
