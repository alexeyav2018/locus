package ru.locus.problem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.UserId;

/**
 * Хранение Черновиков Задачи (ADR-0059).
 *
 * Черновик принадлежит загрузившему, и потому, как у репозиториев личного
 * контура, у каждого метода работы с черновиками есть параметр владельца
 * {@link UserId}, а в каждом SQL — {@code user_id = ?} (ADR-0027): чужой
 * черновик неотличим от несуществующего — чтение его не отдаёт, запись
 * и удаление не трогают. Строка сборки ссылается на Черновик сборки
 * составным ключом с владельцем — чужой база не примет.
 *
 * Исключения — две уборки, {@link #deleteChangedBefore} и {@link #deleteAll},
 * того же класса, что у Черновика сборки (ADR-0044): брошенный черновик
 * убирается, кто бы ни открыл список, и при старте убираются все. Обе
 * только удаляют и возвращают число удалённых — ни одного черновика наружу
 * они не показывают. Перечислены поимённо в
 * {@code OwnerIsRequiredByProblemDraftsTest}.
 *
 * Содержимое лежит в пяти дочерних таблицах и при записи заменяется целиком:
 * сначала строка черновика с проверкой версии, затем дочерние. Транзакцию
 * держит вызывающий — {@code ProblemDraftService}.
 */
@Repository
public class ProblemDraftRepository {

    private static final String COLUMNS =
            "id, user_id, exam_part, condition_text, solution_text, version, changed_by, changed_at, created_at";

    /** Какой из двух списков строк лежит в {@code problem_draft_note}. */
    private enum NoteKind {
        DOUBT,
        NEW_METHOD
    }

    private final JdbcClient database;

    public ProblemDraftRepository(JdbcClient database) {
        this.database = database;
    }

    /** Заводит черновик у владельца с версией ноль и возвращает его идентификатор. */
    public ProblemDraftId create(UserId owner, ProblemDraftContent content, ProblemDraft.Editor editor, Instant now) {
        Long id = database.sql("""
                        insert into problem_draft
                            (user_id, exam_part, condition_text, solution_text, version, changed_by,
                             changed_at, created_at)
                        values (?, ?, ?, ?, 0, ?, ?, ?)
                        returning id
                        """)
                .params(owner.value(), partOrNull(content.part()), content.conditionText(), content.solutionText(),
                        editor.name(), Timestamp.from(now), Timestamp.from(now))
                .query(Long.class)
                .single();
        ProblemDraftId draft = new ProblemDraftId(id);
        writeParts(owner, draft, content);
        return draft;
    }

    /** Черновик владельца; чужой и несуществующий — пусто, одинаково. */
    public Optional<ProblemDraft> find(UserId owner, ProblemDraftId id) {
        return database.sql("select " + COLUMNS + " from problem_draft where user_id = ? and id = ?")
                .params(owner.value(), id.value())
                .query(ProblemDraftRepository::row)
                .optional()
                .map(this::withParts);
    }

    /** Все черновики владельца в порядке заведения. */
    public List<ProblemDraft> findByOwner(UserId owner) {
        return database.sql("select " + COLUMNS + " from problem_draft where user_id = ? order by created_at, id")
                .param(owner.value())
                .query(ProblemDraftRepository::row)
                .list()
                .stream()
                .map(this::withParts)
                .toList();
    }

    /**
     * Записывает содержимое целиком, если хранимая версия равна прочитанной,
     * и возвращает новую версию. Сравнение и запись — одним {@code UPDATE}:
     * между ними чужой записи не вклиниться. Ноль строк — пусто: черновика
     * нет, он чужой или версия другая; различает это вызывающий.
     */
    public OptionalLong write(UserId owner,
                              ProblemDraftId id,
                              long version,
                              ProblemDraftContent content,
                              ProblemDraft.Editor editor,
                              Instant now) {
        Optional<Long> written = database.sql("""
                        update problem_draft
                        set exam_part = ?, condition_text = ?, solution_text = ?, version = version + 1,
                            changed_by = ?, changed_at = ?
                        where user_id = ? and id = ? and version = ?
                        returning version
                        """)
                .params(partOrNull(content.part()), content.conditionText(), content.solutionText(), editor.name(),
                        Timestamp.from(now), owner.value(), id.value(), version)
                .query(Long.class)
                .optional();
        if (written.isEmpty()) {
            return OptionalLong.empty();
        }
        deleteParts(id);
        writeParts(owner, id, content);
        return OptionalLong.of(written.get());
    }

    /**
     * Удаляет черновик владельца с прочитанной версией; дочерние уходят
     * каскадом. Ложь — черновика нет, он чужой или версия другая.
     */
    public boolean delete(UserId owner, ProblemDraftId id, long version) {
        return database.sql("delete from problem_draft where user_id = ? and id = ? and version = ?")
                .params(owner.value(), id.value(), version)
                .update() > 0;
    }

    /**
     * Уборка по сроку — черновики <b>всех</b> владельцев, не изменявшиеся
     * с момента. Возвращает только число удалённых.
     */
    public int deleteChangedBefore(Instant moment) {
        return database.sql("delete from problem_draft where changed_at < ?")
                .param(Timestamp.from(moment))
                .update();
    }

    /** Уборка при старте — все черновики. Возвращает только число удалённых. */
    public int deleteAll() {
        return database.sql("delete from problem_draft").update();
    }

    private void deleteParts(ProblemDraftId id) {
        for (String table : List.of("problem_draft_line", "problem_draft_topic", "problem_draft_method",
                "problem_draft_characteristic", "problem_draft_note")) {
            database.sql("delete from " + table + " where draft_id = ?").param(id.value()).update();
        }
    }

    private void writeParts(UserId owner, ProblemDraftId id, ProblemDraftContent content) {
        for (ProblemSlot slot : ProblemSlot.values()) {
            List<PdfAssemblyOrder.Line> lines = content.lines(slot).lines();
            for (int position = 0; position < lines.size(); position++) {
                PdfAssemblyOrder.Line line = lines.get(position);
                database.sql("""
                                insert into problem_draft_line
                                    (draft_id, user_id, slot, position, assembly_draft_id, page_from, page_to, frame)
                                values (?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                        .params(id.value(), owner.value(), slot.name(), position, line.draft().value(), line.from(),
                                line.to(), line.frame() == null ? null : line.frame().formValue())
                        .update();
            }
        }
        List<TaxonomyNodeId> topics = distinct(content.topics());
        for (int position = 0; position < topics.size(); position++) {
            database.sql("insert into problem_draft_topic (draft_id, position, topic_id) values (?, ?, ?)")
                    .params(id.value(), position, topics.get(position).value())
                    .update();
        }
        List<SolutionMethodId> methods = distinct(content.methods());
        for (int position = 0; position < methods.size(); position++) {
            database.sql("""
                            insert into problem_draft_method (draft_id, position, solution_method_id)
                            values (?, ?, ?)
                            """)
                    .params(id.value(), position, methods.get(position).value())
                    .update();
        }
        List<CharacteristicId> characteristics = distinct(content.characteristics());
        for (int position = 0; position < characteristics.size(); position++) {
            database.sql("""
                            insert into problem_draft_characteristic (draft_id, position, characteristic_id)
                            values (?, ?, ?)
                            """)
                    .params(id.value(), position, characteristics.get(position).value())
                    .update();
        }
        writeNotes(id, NoteKind.DOUBT, content.doubts());
        writeNotes(id, NoteKind.NEW_METHOD, content.newMethods());
    }

    private void writeNotes(ProblemDraftId id, NoteKind kind, List<String> notes) {
        for (int position = 0; position < notes.size(); position++) {
            database.sql("insert into problem_draft_note (draft_id, kind, position, text) values (?, ?, ?, ?)")
                    .params(id.value(), kind.name(), position, notes.get(position))
                    .update();
        }
    }

    /**
     * Дважды названная метка — одна метка, а не ошибка: так же, как
     * у разметки Задачи, составной ключ на повторе упал бы ошибкой базы.
     */
    private static <T> List<T> distinct(List<T> values) {
        return values.stream().distinct().toList();
    }

    private ProblemDraft withParts(Row row) {
        long id = row.id().value();
        ProblemDraftContent content = new ProblemDraftContent(
                row.part(),
                row.conditionText(),
                row.solutionText(),
                linesOf(id, ProblemSlot.CONDITION),
                linesOf(id, ProblemSlot.SOLUTION),
                notesOf(id, NoteKind.DOUBT),
                database.sql("select topic_id from problem_draft_topic where draft_id = ? order by position")
                        .param(id)
                        .query((rs, n) -> new TaxonomyNodeId(rs.getLong(1)))
                        .list(),
                database.sql("""
                                select solution_method_id from problem_draft_method
                                where draft_id = ? order by position
                                """)
                        .param(id)
                        .query((rs, n) -> new SolutionMethodId(rs.getLong(1)))
                        .list(),
                database.sql("""
                                select characteristic_id from problem_draft_characteristic
                                where draft_id = ? order by position
                                """)
                        .param(id)
                        .query((rs, n) -> new CharacteristicId(rs.getLong(1)))
                        .list(),
                notesOf(id, NoteKind.NEW_METHOD));
        return new ProblemDraft(row.id(), row.owner(), content, row.version(), row.changedBy(), row.changedAt(),
                row.createdAt());
    }

    private PdfAssemblyOrder linesOf(long id, ProblemSlot slot) {
        List<PdfAssemblyOrder.Line> lines = new ArrayList<>(database.sql("""
                        select assembly_draft_id, page_from, page_to, frame from problem_draft_line
                        where draft_id = ? and slot = ? order by position
                        """)
                .params(id, slot.name())
                .query((rs, n) -> new PdfAssemblyOrder.Line(
                        new AssemblyDraftId(rs.getLong("assembly_draft_id")),
                        rs.getInt("page_from"),
                        rs.getInt("page_to"),
                        CropFrame.parse(rs.getString("frame"))))
                .list());
        return new PdfAssemblyOrder(lines);
    }

    private List<String> notesOf(long id, NoteKind kind) {
        return database.sql("select text from problem_draft_note where draft_id = ? and kind = ? order by position")
                .params(id, kind.name())
                .query(String.class)
                .list();
    }

    private static String partOrNull(ExamPart part) {
        return part == null ? null : part.name();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        String part = rs.getString("exam_part");
        return new Row(
                new ProblemDraftId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                part == null ? null : ExamPart.valueOf(part),
                rs.getString("condition_text"),
                rs.getString("solution_text"),
                rs.getLong("version"),
                ProblemDraft.Editor.valueOf(rs.getString("changed_by")),
                rs.getTimestamp("changed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Строка {@code problem_draft} без дочерних — до того, как они прочитаны. */
    private record Row(ProblemDraftId id,
                       UserId owner,
                       ExamPart part,
                       String conditionText,
                       String solutionText,
                       long version,
                       ProblemDraft.Editor changedBy,
                       Instant changedAt,
                       Instant createdAt) {
    }
}
