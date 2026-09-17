package ru.locus.mastery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.student.StudentId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.UserId;

/**
 * Хранение отметок Владения — четвёртая область ЛИЧНОГО контура
 * (ADR-0011, ADR-0012, ADR-0039).
 *
 * Как у {@link ru.locus.work.StudentWorkRepository}, у методов, работающих
 * от лица Учителя, есть параметр владельца {@link UserId}, и в каждом их SQL
 * стоит {@code user_id = ?} (ADR-0027): чужая отметка для вызывающего
 * неотличима от несуществующей. Строка ещё и держится ключом на
 * {@code student(id, user_id)}: отметка чужому Ученику не вставится
 * и в обход сервиса.
 *
 * <b>Четыре метода без владельца</b> — класс исключений ADR-0036, вопросы
 * и действия библиотеки к личному контуру о СВОЕЙ сущности:
 * <ul>
 *   <li>{@link #countByTopic} — дерево спрашивает о своей Теме
 *       ({@code NodeContent.on}, {@code requiringTopic}, {@code countVanishing});</li>
 *   <li>{@link #countByMethod} — словарь спрашивает о своём Методе
 *       ({@code DictionaryUsage.ofMethod});</li>
 *   <li>{@link #rehomeTopic} — дерево велит отметкам переехать на
 *       Тему-приёмник ({@code NodeContent.moveTopicContent});</li>
 *   <li>{@link #deleteByTopic} — дерево велит отметкам исчезнуть вместе
 *       со снятой Темой ({@code NodeContent.distributeTopicContent}).</li>
 * </ul>
 * Два последних — действия, а не счёт, и владельца у них нет по существу:
 * их совершает Администратор над общим узлом, и касаются они отметок всех
 * Учителей сразу (ADR-0007). Наружу от вопросов уходит только число;
 * спрашивает библиотека через интерфейс-вопрос; все четыре перечислены
 * поимённо с причиной в {@code OwnerIsRequiredByMasteryTest}.
 * {@code MasteryService} их не зовёт.
 *
 * {@link MasteryStatus#UNKNOWN} в таблицу не пишется (ADR-0039): строка —
 * это суждение, «неизвестно» — отсутствие строки. Единственный вход
 * в таблицу — {@link #put}, и он превращает {@code UNKNOWN} в удаление;
 * поэтому всё, что здесь считает, считает суждения.
 *
 * Правил и проверок прав репозиторий не содержит: право Учителя и правило
 * «пара из разметки Задачи с принятой Работой» ставит {@code MasteryService},
 * право Администратора на перестройку — {@code TaxonomyService}.
 */
@Repository
public class MasteryRepository {

    private final JdbcClient database;

    public MasteryRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Ставит отметку владельца на ячейке своего Ученика: заводит строку либо
     * перезаписывает значение без истории (ADR-0012). {@code UNKNOWN} снимает
     * отметку — удаляет строку (ADR-0039); снятие с пустой ячейки ничего
     * не делает. Отметка чужому Ученику не вставляется — падает на ключе
     * {@code fk_mastery_student}, а не даёт строку.
     */
    public void put(UserId owner, StudentId student, TaxonomyNodeId topic, SolutionMethodId method, MasteryStatus status) {
        if (!status.isJudgement()) {
            database.sql("""
                            delete from mastery
                            where user_id = ? and student_id = ? and topic_id = ? and solution_method_id = ?
                            """)
                    .params(owner.value(), student.value(), topic.value(), method.value())
                    .update();
            return;
        }
        database.sql("""
                        insert into mastery (user_id, student_id, topic_id, solution_method_id, status)
                        values (?, ?, ?, ?, ?)
                        on conflict (user_id, student_id, topic_id, solution_method_id) do update set status = excluded.status
                        """)
                .params(owner.value(), student.value(), topic.value(), method.value(), status.name())
                .update();
    }

    /**
     * Все суждения владельца об Ученике по ячейкам; ячеек без суждения
     * в карте нет — это и есть «неизвестно». Чужой Ученик — пустая карта.
     */
    public Map<Cell, MasteryStatus> findByStudent(UserId owner, StudentId student) {
        Map<Cell, MasteryStatus> marks = new LinkedHashMap<>();
        database.sql("""
                        select topic_id, solution_method_id, status
                        from mastery
                        where user_id = ? and student_id = ?
                        order by topic_id, solution_method_id
                        """)
                .params(owner.value(), student.value())
                .query((rs, rowNum) -> Map.entry(
                        new Cell(new TaxonomyNodeId(rs.getLong("topic_id")),
                                new SolutionMethodId(rs.getLong("solution_method_id"))),
                        MasteryStatus.valueOf(rs.getString("status"))))
                .list()
                .forEach(entry -> marks.put(entry.getKey(), entry.getValue()));
        return marks;
    }

    /** Сколько суждений вынес владелец об Ученике; чужой Ученик — ноль. */
    public int countByStudent(UserId owner, StudentId student) {
        return database.sql("select count(*) from mastery where user_id = ? and student_id = ?")
                .params(owner.value(), student.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Сколько отметок — у ВСЕХ Учителей — стоит на Теме.
     *
     * Метод без владельца, и он здесь законно: Тема — общая библиотека,
     * и спрашивает о ней дерево от лица Администратора, у которого
     * личного контура нет вовсе. Фильтр по владельцу не забыт,
     * а невозможен — подставить некого; наружу уходит только число.
     * Класс исключений ADR-0036; метод перечислен поимённо
     * в {@code OwnerIsRequiredByMasteryTest}.
     */
    public int countByTopic(TaxonomyNodeId topic) {
        return database.sql("select count(*) from mastery where topic_id = ?")
                .param(topic.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Сколько отметок — у ВСЕХ Учителей — опираются на Метод.
     *
     * Метод без владельца по той же причине, что {@link #countByTopic}:
     * Метод — общий словарь, спрашивает Администратор, наружу — число
     * (ADR-0036, {@code OwnerIsRequiredByMasteryTest}).
     */
    public int countByMethod(SolutionMethodId method) {
        return database.sql("select count(*) from mastery where solution_method_id = ?")
                .param(method.value())
                .query(Integer.class)
                .single();
    }

    /**
     * Перевешивает отметки ВСЕХ Учителей с Темы {@code from} на Тему
     * {@code to} — при углублении Темы с содержимым (ADR-0007) — и сливает
     * их с теми, что на приёмнике уже есть (ADR-0039): у одной тройки
     * «Учитель, Ученик, Метод» на приёмнике остаётся одна строка; если
     * суждения на двух Темах совпадали — оно и остаётся, если разошлись —
     * приёмнику ставится {@link MasteryStatus#UNCERTAIN}.
     *
     * Три запроса в порядке «слить → убрать занятые → перевесить»;
     * порядок важен: перевешивание строки на занятую ячейку упало бы
     * на первичном ключе. Атомарность — дело транзакции
     * {@code TaxonomyService}.
     *
     * Метод без владельца: действие дерева над своей Темой, касающееся
     * отметок всех Учителей сразу, — владельца у операции нет по существу
     * (ADR-0036, {@code OwnerIsRequiredByMasteryTest}).
     */
    public void rehomeTopic(TaxonomyNodeId from, TaxonomyNodeId to) {
        database.sql("""
                        update mastery receiver
                        set status = ?
                        from mastery moving
                        where receiver.topic_id = ?
                          and moving.topic_id = ?
                          and moving.user_id = receiver.user_id
                          and moving.student_id = receiver.student_id
                          and moving.solution_method_id = receiver.solution_method_id
                          and moving.status <> receiver.status
                        """)
                .params(MasteryStatus.UNCERTAIN.name(), to.value(), from.value())
                .update();
        database.sql("""
                        delete from mastery moving
                        where moving.topic_id = ?
                          and exists (select 1
                                      from mastery receiver
                                      where receiver.topic_id = ?
                                        and receiver.user_id = moving.user_id
                                        and receiver.student_id = moving.student_id
                                        and receiver.solution_method_id = moving.solution_method_id)
                        """)
                .params(from.value(), to.value())
                .update();
        database.sql("update mastery set topic_id = ? where topic_id = ?")
                .params(to.value(), from.value())
                .update();
    }

    /**
     * Удаляет отметки ВСЕХ Учителей с Темы — при снятии Темы
     * с распределением: Задачи разъезжаются по приёмникам, а отметки
     * не распределяются, а исчезают (ADR-0007). Соседние Темы не трогаются.
     *
     * Метод без владельца по той же причине, что {@link #rehomeTopic}
     * (ADR-0036, {@code OwnerIsRequiredByMasteryTest}).
     */
    public void deleteByTopic(TaxonomyNodeId topic) {
        database.sql("delete from mastery where topic_id = ?")
                .param(topic.value())
                .update();
    }
}
