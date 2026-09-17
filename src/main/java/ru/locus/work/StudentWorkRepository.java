package ru.locus.work;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.assignment.AssignmentId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.UserId;

/**
 * Хранение Работ и их файлов — третья область ЛИЧНОГО контура
 * (ADR-0015, ADR-0038).
 *
 * Как у {@link ru.locus.assignment.AssignmentRepository}, у каждого метода
 * есть параметр владельца {@link UserId}, и в каждом SQL стоит
 * {@code user_id = ?} (ADR-0027): чужая Работа для вызывающего неотличима
 * от несуществующей — чтение отдаёт пусто, правка и удаление не меняют
 * ни одной строки. Исключений нет вовсе: библиотека о Работах ничего
 * не спрашивает, класс из ADR-0036 не задействован. Это сторожит
 * {@code OwnerIsRequiredByWorksTest}; поведение проверяет
 * {@code StudentWorkRepositoryTest} двумя владельцами на каждом методе.
 *
 * С Работы уходят два составных ключа — на {@code assignment_problem
 * (assignment_id, problem_id)} и на {@code assignment(id, user_id)}:
 * Работа по Задаче не из состава Задания и Работа по чужому Заданию
 * не могут существовать, им не на что сослаться. Уникальность
 * {@code (assignment_id, problem_id)} — «на паре Работа одна» (ADR-0038):
 * вторая вставка на ту же пару падает
 * {@link org.springframework.dao.DuplicateKeyException}, и сервис
 * превращает её в отказ с текстом. Здесь все три ограничения —
 * последний рубеж, а не способ проверки (design.md, «Схема»).
 *
 * Ученика в таблице нет — он у Задания; {@link #findByStudent},
 * {@link #countByStudent} и {@link #countCheckedByPairs} идут соединением
 * с {@code assignment}.
 * Колонки «проверена» нет: «не проверена» — это {@code verdict IS NULL}.
 *
 * Правил и проверок прав репозиторий не содержит: их ставит
 * {@link StudentWorkService}. Хранилища файлов он тоже не знает —
 * здесь только ключи; кладёт и убирает файлы сервис.
 */
@Repository
public class StudentWorkRepository {

    private static final String COLUMNS =
            "w.id, w.user_id, w.assignment_id, w.problem_id, w.received_on, w.verdict, w.note";

    private final JdbcClient database;

    public StudentWorkRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Заводит Работу у владельца вместе с файлами и возвращает
     * её идентификатор.
     *
     * Файлы уходят одной вставкой, {@code position} — от 1 в порядке
     * переданного списка: это порядок загрузки. Атомарность двух вставок —
     * дело транзакции сервиса. Вторая Работа на паре «Задание × Задача»,
     * Работа по Задаче не из состава и по чужому Заданию не вставляются —
     * падают на ключах, а не дают строку.
     *
     * @param verdict {@code null} — Работа принята без вердикта
     */
    public StudentWorkId create(UserId owner,
                                AssignmentId assignment,
                                ProblemId problem,
                                LocalDate receivedOn,
                                Verdict verdict,
                                String note,
                                List<FileKey> files) {
        Long id = database.sql("""
                        insert into student_work (user_id, assignment_id, problem_id, received_on, verdict, note)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(owner.value(), assignment.value(), problem.value(), receivedOn,
                        verdict == null ? null : verdict.name(), note)
                .query(Long.class)
                .single();
        insertFiles(owner, id, files, 1);
        return new StudentWorkId(id);
    }

    /** Работа владельца с файлами в порядке загрузки; чужая или несуществующая — пусто. */
    public Optional<StudentWork> findById(UserId owner, StudentWorkId id) {
        List<Row> rows = database.sql("select " + COLUMNS + " from student_work w where w.user_id = ? and w.id = ?")
                .params(owner.value(), id.value())
                .query(StudentWorkRepository::row)
                .list();
        return withFiles(owner, rows).stream().findFirst();
    }

    /**
     * Работы владельца по Заданию — в порядке приёма; чужое или
     * несуществующее Задание — пустой список. Работ у Задания не больше,
     * чем Задач в составе: на паре Работа одна.
     */
    public List<StudentWork> findByAssignment(UserId owner, AssignmentId assignment) {
        List<Row> rows = database.sql(
                        "select " + COLUMNS + " from student_work w where w.user_id = ? and w.assignment_id = ? order by w.id")
                .params(owner.value(), assignment.value())
                .query(StudentWorkRepository::row)
                .list();
        return withFiles(owner, rows);
    }

    /**
     * Работы владельца от Ученика — новые первыми: по дате получения,
     * затем по идентификатору по убыванию, чтобы Работы одного дня
     * не менялись местами от чтения к чтению. Ученик — у Задания,
     * потому соединение с {@code assignment}; чужой Ученик — пусто.
     */
    public List<StudentWork> findByStudent(UserId owner, StudentId student) {
        List<Row> rows = database.sql("""
                        select %s
                        from student_work w
                        join assignment a on a.id = w.assignment_id and a.user_id = w.user_id
                        where w.user_id = ? and a.student_id = ?
                        order by w.received_on desc, w.id desc
                        """.formatted(COLUMNS))
                .params(owner.value(), student.value())
                .query(StudentWorkRepository::row)
                .list();
        return withFiles(owner, rows);
    }

    /**
     * Добавляет файлы в конец Работы владельца: позиции продолжают счёт
     * с последней занятой. Чужая Работа не трогается — строки файлов
     * держатся ключом на {@code student_work(id, user_id)}, и вставка
     * с чужим владельцем падает, а не проходит.
     */
    public void addFiles(UserId owner, StudentWorkId work, List<FileKey> files) {
        int last = database.sql("select coalesce(max(position), 0) from student_work_file where user_id = ? and student_work_id = ?")
                .params(owner.value(), work.value())
                .query(Integer.class)
                .single();
        insertFiles(owner, work.value(), files, last + 1);
    }

    /**
     * Удаляет строку файла Работы владельца; чужую не трогает. Что файл
     * у Работы не последний, проверяет сервис — здесь правила нет.
     */
    public void deleteFile(UserId owner, StudentWorkId work, StudentWorkFileId file) {
        database.sql("delete from student_work_file where user_id = ? and student_work_id = ? and id = ?")
                .params(owner.value(), work.value(), file.value())
                .update();
    }

    /**
     * Ставит, меняет или снимает вердикт вместе с примечанием у Работы
     * владельца; чужую не трогает.
     *
     * @param verdict {@code null} — снять вердикт: Работа снова «не проверена»
     */
    public void setVerdict(UserId owner, StudentWorkId work, Verdict verdict, String note) {
        database.sql("update student_work set verdict = ?, note = ? where user_id = ? and id = ?")
                .params(verdict == null ? null : verdict.name(), note, owner.value(), work.value())
                .update();
    }

    /**
     * Удаляет Работу владельца; чужую не трогает.
     *
     * Строки файлов уходят каскадом {@code fk_student_work_file_work}:
     * файл без Работы — не сущность. Из хранилища их убирает сервис,
     * собрав ключи до удаления. Задание и Ученик остаются — на них ключи
     * без каскада, и удаление Работы их не касается.
     */
    public void delete(UserId owner, StudentWorkId id) {
        database.sql("delete from student_work where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .update();
    }

    /**
     * Какие из Заданий владельца имеют хотя бы одну Работу — одним запросом
     * на весь список: Раздача проверяется целиком перед удалением.
     * Чужие Задания в ответ не попадают, даже если по ним есть Работы
     * у их владельца.
     */
    public Set<AssignmentId> assignmentsWithWork(UserId owner, Collection<AssignmentId> assignments) {
        if (assignments.isEmpty()) {
            return Set.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        assignments.forEach(assignment -> params.add(assignment.value()));
        return new HashSet<>(database.sql("""
                        select distinct assignment_id
                        from student_work
                        where user_id = ? and assignment_id in (%s)
                        """.formatted(String.join(", ", Collections.nCopies(assignments.size(), "?"))))
                .params(params)
                .query(Long.class)
                .list()
                .stream()
                .map(AssignmentId::new)
                .toList());
    }

    /** Сколько Работ принято от Ученика владельца; чужой Ученик — ноль. */
    public int countByStudent(UserId owner, StudentId student) {
        return database.sql("""
                        select count(*)
                        from student_work w
                        join assignment a on a.id = w.assignment_id and a.user_id = w.user_id
                        where w.user_id = ? and a.student_id = ?
                        """)
                .params(owner.value(), student.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Справка «решено N из M» по каждой паре «Тема × Метод», на которой
     * у Ученика владельца есть хотя бы одна ПРОВЕРЕННАЯ Работа, — одним
     * запросом на всего Ученика, а не по ячейке: экран приёма показывает
     * справку у каждой ячейки каждой Задачи с Работой.
     *
     * Пара берётся из разметки Задачи, по которой Работа: Работа с Задачей
     * на двух Темах и двух Методах считается на всех четырёх парах. Пары
     * без проверенных Работ в карте нет; непроверенные Работы
     * ({@code verdict IS NULL}) не считаются вовсе — до вердикта Работа
     * о владении не говорит ничего (ADR-0038). Чужой Ученик — пустая карта.
     */
    public Map<ProblemPair, Solved> countCheckedByPairs(UserId owner, StudentId student) {
        Map<ProblemPair, Solved> solved = new LinkedHashMap<>();
        database.sql("""
                        select pt.topic_id, pm.solution_method_id,
                               count(*) filter (where w.verdict = ?) as correct,
                               count(*) as checked
                        from student_work w
                        join assignment a on a.id = w.assignment_id and a.user_id = w.user_id
                        join problem_topic pt on pt.problem_id = w.problem_id
                        join problem_solution_method pm on pm.problem_id = w.problem_id
                        where w.user_id = ? and a.student_id = ? and w.verdict is not null
                        group by pt.topic_id, pm.solution_method_id
                        order by pt.topic_id, pm.solution_method_id
                        """)
                .params(Verdict.CORRECT.name(), owner.value(), student.value())
                .query((rs, rowNum) -> Map.entry(
                        new ProblemPair(new TaxonomyNodeId(rs.getLong("topic_id")),
                                new SolutionMethodId(rs.getLong("solution_method_id"))),
                        new Solved(rs.getInt("correct"), rs.getInt("checked"))))
                .list()
                .forEach(entry -> solved.put(entry.getKey(), entry.getValue()));
        return solved;
    }

    private void insertFiles(UserId owner, long work, List<FileKey> files, int firstPosition) {
        if (files.isEmpty()) {
            return;
        }
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            values.add(work);
            values.add(owner.value());
            values.add(files.get(i).value());
            values.add(firstPosition + i);
        }
        database.sql("insert into student_work_file (student_work_id, user_id, file_key, position) values "
                        + String.join(", ", Collections.nCopies(files.size(), "(?, ?, ?, ?)")))
                .params(values)
                .update();
    }

    /** Файлы всего списка одним запросом; порядок Работ сохраняется, порядок файлов — по {@code position}. */
    private List<StudentWork> withFiles(UserId owner, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        rows.forEach(row -> params.add(row.id().value()));
        Map<Long, List<StudentWorkFile>> files = new LinkedHashMap<>();
        database.sql("""
                        select student_work_id, id, file_key, position
                        from student_work_file
                        where user_id = ? and student_work_id in (%s)
                        order by student_work_id, position
                        """.formatted(String.join(", ", Collections.nCopies(rows.size(), "?"))))
                .params(params)
                .query((rs, rowNum) -> Map.entry(rs.getLong("student_work_id"), new StudentWorkFile(
                        new StudentWorkFileId(rs.getLong("id")), new FileKey(rs.getString("file_key")), rs.getInt("position"))))
                .list()
                .forEach(entry -> files.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        return rows.stream()
                .map(row -> row.withFiles(files.getOrDefault(row.id().value(), List.of())))
                .toList();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        String verdict = rs.getString("verdict");
        return new Row(
                new StudentWorkId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                new AssignmentId(rs.getLong("assignment_id")),
                new ProblemId(rs.getLong("problem_id")),
                rs.getObject("received_on", LocalDate.class),
                verdict == null ? null : Verdict.valueOf(verdict),
                rs.getString("note"));
    }

    /** Строка {@code student_work} без файлов — файлы дочитываются отдельно. */
    private record Row(StudentWorkId id,
                       UserId owner,
                       AssignmentId assignment,
                       ProblemId problem,
                       LocalDate receivedOn,
                       Verdict verdict,
                       String note) {

        StudentWork withFiles(List<StudentWorkFile> files) {
            return new StudentWork(id, owner, assignment, problem, receivedOn, verdict, note, files);
        }
    }
}
