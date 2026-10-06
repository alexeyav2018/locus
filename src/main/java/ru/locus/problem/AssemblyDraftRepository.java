package ru.locus.problem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.user.UserId;

/**
 * Хранение черновиков сборки (ADR-0044).
 *
 * Черновик принадлежит загрузившему, и потому, как у репозиториев личного
 * контура, у каждого метода работы с черновиками есть параметр владельца
 * {@link UserId}, а в каждом SQL — {@code user_id = ?} (ADR-0027): чужой
 * черновик неотличим от несуществующего — чтение его не отдаёт, удаление
 * не трогает.
 *
 * Исключения — две уборки, {@link #deleteCreatedBefore} и {@link #deleteAll}:
 * брошенный черновик убирается, кто бы ни открыл инструмент, и при старте
 * убираются все. Обе только удаляют и возвращают имена файлов, чтобы
 * снести их с диска, — ни одного черновика наружу они не показывают.
 * Перечислены поимённо в {@code OwnerIsRequiredByDraftsTest}.
 *
 * Файлов репозиторий не знает: кладёт и сносит их {@link AssemblyDraftService}.
 */
@Repository
public class AssemblyDraftRepository {

    private static final String COLUMNS =
            "id, user_id, original_name, kind, content_type, page_count, file_name, created_at";

    private final JdbcClient database;

    public AssemblyDraftRepository(JdbcClient database) {
        this.database = database;
    }

    /** Заводит черновик у владельца и возвращает его идентификатор. */
    public AssemblyDraftId create(UserId owner,
                                  String originalName,
                                  AssemblyDraft.Kind kind,
                                  String contentType,
                                  int pageCount,
                                  String fileName,
                                  Instant createdAt) {
        Long id = database.sql("""
                        insert into assembly_draft
                            (user_id, original_name, kind, content_type, page_count, file_name, created_at)
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(owner.value(), originalName, kind.name(), contentType, pageCount, fileName,
                        Timestamp.from(createdAt))
                .query(Long.class)
                .single();
        return new AssemblyDraftId(id);
    }

    /**
     * Черновики владельца из названных — в порядке идентификаторов; чужие
     * и несуществующие просто не возвращаются, и отличить одни от других
     * по ответу нельзя.
     */
    public List<AssemblyDraft> findByIds(UserId owner, Collection<AssemblyDraftId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        ids.forEach(id -> params.add(id.value()));
        return database.sql("select " + COLUMNS + " from assembly_draft where user_id = ? and id in ("
                        + placeholders(ids.size()) + ") order by id")
                .params(params)
                .query(AssemblyDraftRepository::row)
                .list();
    }

    /**
     * Удаляет черновики владельца из названных и возвращает имена их файлов;
     * чужие не трогаются.
     */
    public List<String> deleteByIds(UserId owner, Collection<AssemblyDraftId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(owner.value());
        ids.forEach(id -> params.add(id.value()));
        return database.sql("delete from assembly_draft where user_id = ? and id in ("
                        + placeholders(ids.size()) + ") returning file_name")
                .params(params)
                .query(String.class)
                .list();
    }

    /**
     * Уборка по сроку — черновики <b>всех</b> владельцев, загруженные раньше
     * момента. Возвращает только имена файлов.
     */
    public List<String> deleteCreatedBefore(Instant moment) {
        return database.sql("delete from assembly_draft where created_at < ? returning file_name")
                .param(Timestamp.from(moment))
                .query(String.class)
                .list();
    }

    /** Уборка при старте — все черновики. Возвращает только имена файлов. */
    public List<String> deleteAll() {
        return database.sql("delete from assembly_draft returning file_name")
                .query(String.class)
                .list();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static AssemblyDraft row(ResultSet rs, int rowNum) throws SQLException {
        return new AssemblyDraft(
                new AssemblyDraftId(rs.getLong("id")),
                new UserId(rs.getLong("user_id")),
                rs.getString("original_name"),
                AssemblyDraft.Kind.valueOf(rs.getString("kind")),
                rs.getString("content_type"),
                rs.getInt("page_count"),
                rs.getString("file_name"),
                rs.getTimestamp("created_at").toInstant());
    }
}
