package ru.locus.problem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethod;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Хранение Задач и их разметки.
 *
 * Задача — общая библиотека: параметра владельца здесь нет и быть не должно
 * (ADR-0027). Передать его сюда некуда, поэтому «на всякий случай
 * отфильтровать» физически не получится, а фильтр, применённый к библиотеке,
 * сделал бы её невидимой, а выдачу заданий — невозможной.
 *
 * Разметка лежит в трёх связующих таблицах и читается отдельными запросами,
 * а не одним соединением: соединение трёх связей «многие ко многим» даёт
 * произведение строк, и разбирать его обратно пришлось бы в Java — с риском
 * потерять метку, встречающуюся один раз.
 *
 * Правил Задачи и проверок прав репозиторий не содержит: их ставит
 * {@link ProblemService}.
 */
@Repository
public class ProblemRepository {

    /**
     * Столбцы строки Задачи — одним списком на все чтения: разойдись списки
     * у поиска и у одиночного чтения, одна и та же Задача оказалась бы
     * в одном месте с текстом, а в другом без. Обычной строкой с пробелами
     * по краям, а не текстовым блоком: текстовый блок отсекает пробел в конце
     * строки, и {@code select} склеился бы с первым столбцом.
     */
    private static final String COLUMNS = " p.id, p.exam_part, p.condition_file_key, p.solution_file_key,"
            + " p.condition_text, p.solution_text, p.condition_evidence_key, p.solution_evidence_key,"
            + " p.text_version ";

    private final JdbcClient database;

    public ProblemRepository(JdbcClient database) {
        this.database = database;
    }

    /**
     * Заводит Задачу вместе со всей разметкой и возвращает её идентификатор —
     * он же номер.
     *
     * Ключи файлов приходят готовыми: файлы кладутся до записи, потому что
     * Задача без файла выглядела бы настоящей, а забытый в хранилище файл
     * не виден никому (design.md, «Порядок укладки файлов»).
     */
    public ProblemId create(ExamPart part,
                            FileKey conditionFile,
                            FileKey solutionFile,
                            List<TaxonomyNodeId> topics,
                            List<SolutionMethodId> methods,
                            List<CharacteristicId> characteristics) {
        return create(part, conditionFile, solutionFile, null, topics, methods, characteristics);
    }

    /**
     * Заводит Задачу с текстом либо без — по тому, пришёл ли {@code text}.
     *
     * У Задачи с текстом {@code conditionFile} и {@code solutionFile} —
     * уже положенные отрисовки, а Улики лежат в самом {@code text}. Версия
     * текста новой Задачи — ноль, какой бы ни пришла в записи: счёт правок
     * начинается с заведения.
     */
    public ProblemId create(ExamPart part,
                            FileKey conditionFile,
                            FileKey solutionFile,
                            ProblemText text,
                            List<TaxonomyNodeId> topics,
                            List<SolutionMethodId> methods,
                            List<CharacteristicId> characteristics) {
        Long id = database.sql("""
                        insert into problem (exam_part, condition_file_key, solution_file_key,
                                             condition_text, solution_text,
                                             condition_evidence_key, solution_evidence_key)
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(part.name(), conditionFile.value(), solutionFile.value(),
                        text == null ? null : text.condition(),
                        text == null ? null : text.solution(),
                        keyOrNull(text == null ? null : text.conditionEvidence()),
                        keyOrNull(text == null ? null : text.solutionEvidence()))
                .query(Long.class)
                .single();
        ProblemId problem = new ProblemId(id);
        writeMarkup(problem, topics, methods, characteristics);
        return problem;
    }

    public Optional<Problem> findById(ProblemId id) {
        return database.sql("""
                        select """ + COLUMNS + """
                        from problem p
                        where p.id = ?
                        """)
                .param(id.value())
                .query(ProblemRepository::row)
                .optional()
                .map(this::withMarkup);
    }

    /**
     * Задачи, размеченные указанной Темой, по номеру.
     *
     * Обхода поддерева здесь нет намеренно: список отвечает на вопрос «что
     * лежит на этой Теме», а поиск по всей библиотеке придёт
     * с {@code library-search}.
     */
    public List<Problem> findByTopic(TaxonomyNodeId topic) {
        List<Row> rows = database.sql("""
                        select """ + COLUMNS + """
                        from problem p
                        join problem_topic pt on pt.problem_id = p.id
                        where pt.topic_id = ?
                        order by p.id
                        """)
                .param(topic.value())
                .query(ProblemRepository::row)
                .list();
        return rows.stream().map(this::withMarkup).toList();
    }

    /**
     * Задачи по списку номеров — одним запросом, а не по одному на номер.
     *
     * Порядок ответа — по номеру, как у {@link #findByTopic}: запрос
     * не знает порядка, в котором номера пришли, и восстанавливает его
     * тот, кто спрашивал ({@code ProblemService.problems}). Отсутствующий
     * номер строки не даёт — ответ короче запроса, и отличить «нет такой»
     * от «есть» обязан вызывающий, сверив списки. Пустой список — пустой
     * ответ без запроса: {@code in ()} в SQL не существует.
     */
    public List<Problem> findByIds(List<ProblemId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Row> rows = database.sql("""
                        select """ + COLUMNS + """
                        from problem p
                        where p.id in (""" + placeholders(ids.size()) + """
                        )
                        order by p.id
                        """)
                .params(ids.stream().map(ProblemId::value).toList())
                .query(ProblemRepository::row)
                .list();
        return withMarkup(rows);
    }

    /**
     * Отбор Задач по разметке — то, чем библиотека ищется (ADR-0031).
     *
     * <p>Темы приходят <b>готовым</b> списком: поддерево разворачивает
     * рубрикатор, а не этот запрос. Второй рекурсивный обход завёл бы второй
     * предел глубины, и разошлись бы они молча — стандарт проекта требует
     * одного обхода на весь проект (standards.md, «Данные»).
     *
     * <p>Условия складываются подзапросами {@code exists}, а не соединением
     * со снятием дублей через {@code distinct}. Ответ получился бы верный,
     * но Задача, размеченная двумя Темами одного поддерева, порождает две
     * строки, и правильность держалась бы на том, что в списке выборки нет
     * ни одного различающего столбца. Стоит однажды добавить туда, скажем,
     * имя Темы — и дубли вернутся, тихо. {@code exists} не порождает дублей
     * вовсе.
     *
     * <p>Способ соединения меняет не вид подзапроса, а их число:
     * {@link MatchMode#ANY} — один {@code exists} с {@code in (...)},
     * {@link MatchMode#ALL} — по одному {@code exists} на значение.
     *
     * <p>Незаданное условие в текст запроса не попадает вовсе — оно
     * не подставляется «всегда истинным» выражением: запрос должен читаться
     * так же, как выглядит форма.
     *
     * @param topics           Темы, уже развёрнутые из поддерева;
     *                         {@code null} — условия по узлу нет. Пустым
     *                         список не бывает: поддерево всегда содержит
     *                         сам узел
     * @param methods          выбранные Методы; пустой список — условия нет
     * @param characteristics  выбранные Характеристики; пустой — условия нет
     * @param part             Часть; {@code null} — условия нет
     * @throws IllegalArgumentException если список Тем пуст — это испорченный
     *                                  вызов, и пустой результат был бы
     *                                  правдоподобным ответом на него
     */
    public List<Problem> search(List<TaxonomyNodeId> topics,
                                List<SolutionMethodId> methods,
                                MatchMode methodMode,
                                List<CharacteristicId> characteristics,
                                MatchMode characteristicMode,
                                ExamPart part) {
        if (topics != null && topics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Пустой список Тем: поддерево всегда содержит сам узел, значит вызов испорчен");
        }
        StringBuilder sql = new StringBuilder("""
                select """ + COLUMNS + """
                from problem p
                where 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();

        if (topics != null) {
            appendAny(sql, parameters, "problem_topic", "topic_id",
                    topics.stream().map(TaxonomyNodeId::value).toList());
        }
        appendMarkup(sql, parameters, "problem_solution_method", "solution_method_id", methodMode,
                methods == null ? List.of() : methods.stream().map(SolutionMethodId::value).toList());
        appendMarkup(sql, parameters, "problem_characteristic", "characteristic_id", characteristicMode,
                characteristics == null ? List.of() : characteristics.stream().map(CharacteristicId::value).toList());
        if (part != null) {
            sql.append(" and p.exam_part = ?");
            parameters.add(part.name());
        }
        sql.append(" order by p.id");

        List<Row> rows = database.sql(sql.toString())
                .params(parameters)
                .query(ProblemRepository::row)
                .list();
        return withMarkup(rows);
    }

    /**
     * Условие по одной связующей таблице.
     *
     * <p><b>Пустой список — это отсутствие условия, а не условие из пустого
     * набора</b>, и решается это здесь, один раз на все три измерения.
     * Понятое буквально, «все сразу» из нуля значений истинно для всякой
     * Задачи, а «любое из» из нуля — ложно для всякой; второе вернуло бы
     * пусто на непустой библиотеке, объяснимо изнутри и необъяснимо снаружи.
     * Ни одно из двух прочтений не имеется в виду, когда учитель просто
     * не тронул список.
     */
    private static void appendMarkup(StringBuilder sql,
                                     List<Object> parameters,
                                     String table,
                                     String column,
                                     MatchMode mode,
                                     List<Long> values) {
        if (values.isEmpty()) {
            return;
        }
        if (mode == MatchMode.ALL) {
            for (Long value : values) {
                appendAny(sql, parameters, table, column, List.of(value));
            }
        } else {
            appendAny(sql, parameters, table, column, values);
        }
    }

    /** «У Задачи есть хотя бы одна из этих меток» — один {@code exists}. */
    private static void appendAny(StringBuilder sql,
                                  List<Object> parameters,
                                  String table,
                                  String column,
                                  List<Long> values) {
        sql.append(" and exists (select 1 from ").append(table)
                .append(" t where t.problem_id = p.id and t.").append(column)
                .append(" in (").append(placeholders(values.size())).append("))");
        parameters.addAll(values);
    }

    /**
     * Значения уходят параметрами; в текст запроса подставляется только
     * число вопросительных знаков. Собранной строкой запрос быть обязан —
     * незаданное условие не должно в нём появляться, — но собирается она
     * из заранее известных кусков, и ни одно значение в неё не попадает.
     */
    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /**
     * Методы, которыми размечена хотя бы одна Задача указанной Темы.
     *
     * Выборка идёт <b>по разметке Задач</b>, а не по словарю: у записи словаря
     * нет и не может быть принадлежности Теме — приём кочует между разделами
     * математики, а расщеплённый по разделам Метод обратно уже не собрать
     * (ADR-0010). {@code distinct} — потому что Метод встречается в нескольких
     * Задачах одной Темы, а показать его надо один раз.
     *
     * Порядок задаётся {@code lower(name)}, как и в самом словаре, и ключ
     * сортировки приходится держать в списке выборки: {@code distinct}
     * в PostgreSQL иначе не умеет упорядочивать по выражению. Прочитанным
     * этот столбец не бывает — он существует ради порядка.
     */
    public List<SolutionMethod> findMethodsUsedInTopic(TaxonomyNodeId topic) {
        return database.sql("""
                        select distinct m.id, m.name, lower(m.name) as sort_name
                        from solution_method m
                        join problem_solution_method psm on psm.solution_method_id = m.id
                        join problem_topic pt on pt.problem_id = psm.problem_id
                        where pt.topic_id = ?
                        order by sort_name
                        """)
                .param(topic.value())
                .query((rs, rowNum) -> new SolutionMethod(new SolutionMethodId(rs.getLong("id")), rs.getString("name")))
                .list();
    }


    /**
     * Пары «Тема × Метод», порождённые разметкой Задач всего каталога, —
     * ячейки экрана Владения (инвариант 4: не декартово произведение
     * словарей). Один запрос на весь рубрикатор, а не {@link
     * #findMethodsUsedInTopic} по каждой Теме дерева: у экрана Владения
     * все Темы сразу, и N запросов на N Тем — лишнее.
     *
     * Порядок задаётся самим {@code order by}: строки идут по Теме,
     * внутри Темы — по Методу, и группировка в {@link LinkedHashMap}
     * его сохраняет.
     */
    public Map<TaxonomyNodeId, List<SolutionMethodId>> findMethodsUsedByTopic() {
        Map<TaxonomyNodeId, List<SolutionMethodId>> byTopic = new LinkedHashMap<>();
        database.sql("""
                        select distinct pt.topic_id, psm.solution_method_id
                        from problem_topic pt
                        join problem_solution_method psm on psm.problem_id = pt.problem_id
                        order by pt.topic_id, psm.solution_method_id
                        """)
                .query((rs, rowNum) -> Map.entry(
                        new TaxonomyNodeId(rs.getLong("topic_id")),
                        new SolutionMethodId(rs.getLong("solution_method_id"))))
                .list()
                .forEach(entry -> byTopic.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byTopic;
    }

    /** Сколько Задач размечено этой Темой — для проверок дерева. */
    public int countByTopic(TaxonomyNodeId topic) {
        return database.sql("select count(*) from problem_topic where topic_id = ?")
                .param(topic.value())
                .query(Integer.class)
                .single();
    }

    /** Сколько Задач размечено этим Методом — для проверки удаления записи словаря. */
    public int countByMethod(SolutionMethodId method) {
        return database.sql("select count(*) from problem_solution_method where solution_method_id = ?")
                .param(method.value())
                .query(Integer.class)
                .single();
    }

    /** Сколько Задач размечено этой Характеристикой. */
    public int countByCharacteristic(CharacteristicId characteristic) {
        return database.sql("select count(*) from problem_characteristic where characteristic_id = ?")
                .param(characteristic.value())
                .query(Integer.class)
                .single();
    }

    public void changePart(ProblemId id, ExamPart part) {
        database.sql("update problem set exam_part = ? where id = ?")
                .params(part.name(), id.value())
                .update();
    }

    public void changeConditionFile(ProblemId id, FileKey key) {
        database.sql("update problem set condition_file_key = ? where id = ?")
                .params(key.value(), id.value())
                .update();
    }

    public void changeSolutionFile(ProblemId id, FileKey key) {
        database.sql("update problem set solution_file_key = ? where id = ?")
                .params(key.value(), id.value())
                .update();
    }

    /**
     * Меняет текст одного слота Задачи с текстом вместе с его отрисовкой
     * и поднимает версию текста (design.md изменения {@code problem-text},
     * Р7, Р10). Второй слот не трогается.
     *
     * <p>Версия сверяется <b>в самом {@code UPDATE}</b>, а не чтением перед
     * ним: между чтением и записью успела бы пройти чужая правка, и она
     * исчезла бы молча. Ноль изменённых строк — значит, хранимая версия
     * ушла вперёд.
     *
     * @param rendered уже положенная отрисовка нового текста
     * @throws ProblemTextChangedException если версия не совпала или текста
     *                                     у Задачи нет
     */
    public void changeText(ProblemId id, ProblemSlot slot, String text, FileKey rendered, int expectedVersion) {
        String column = column(slot);
        int changed = database.sql("""
                        update problem
                        set %1$s_text = ?, %1$s_file_key = ?, text_version = text_version + 1
                        where id = ? and text_version = ? and condition_text is not null
                        """.formatted(column))
                .params(text, rendered.value(), id.value(), expectedVersion)
                .update();
        if (changed == 0) {
            throw new ProblemTextChangedException();
        }
    }

    /**
     * Записывает текст Задаче без текста (design.md изменения
     * {@code problem-text}, Р5): в слоты ложатся отрисовки, а прежние
     * приложенные PDF становятся Уликами своих слотов — ключ переезжает
     * в колонку Улики, файл не копируется.
     *
     * <p>Переезд идёт одним {@code UPDATE}: правые части присваиваний
     * в PostgreSQL видят строку до изменения, поэтому Улика получает
     * прежний ключ, а не только что записанную отрисовку.
     *
     * @throws ProblemTextChangedException если у Задачи успел появиться текст
     *                                     или версия не совпала
     */
    public void writeText(ProblemId id,
                          String condition,
                          String solution,
                          FileKey conditionRendered,
                          FileKey solutionRendered,
                          int expectedVersion) {
        int changed = database.sql("""
                        update problem
                        set condition_evidence_key = condition_file_key,
                            solution_evidence_key = solution_file_key,
                            condition_file_key = ?,
                            solution_file_key = ?,
                            condition_text = ?,
                            solution_text = ?,
                            text_version = text_version + 1
                        where id = ? and text_version = ? and condition_text is null
                        """)
                .params(conditionRendered.value(), solutionRendered.value(), condition, solution,
                        id.value(), expectedVersion)
                .update();
        if (changed == 0) {
            throw new ProblemTextChangedException();
        }
    }

    /**
     * Заменяет Улику слота Задачи с текстом. Версию текста не трогает:
     * Улика — снимок рядом с текстом, а не сам текст (Р7).
     *
     * <p>Задаче без текста Улику не записать, и держит это не только сервис:
     * запись отклонит ограничение базы «Улика только при тексте».
     */
    public void changeEvidence(ProblemId id, ProblemSlot slot, FileKey evidence) {
        database.sql("update problem set %s_evidence_key = ? where id = ?".formatted(column(slot)))
                .params(evidence.value(), id.value())
                .update();
    }

    /**
     * Имя слота в именах столбцов. В текст запроса подставляется только
     * одно из двух известных слов, а не пришедшее значение.
     */
    private static String column(ProblemSlot slot) {
        return switch (slot) {
            case CONDITION -> "condition";
            case SOLUTION -> "solution";
        };
    }

    private static String keyOrNull(FileKey key) {
        return key == null ? null : key.value();
    }

    /**
     * Заменяет разметку целиком: старые связи снимаются, новые пишутся.
     *
     * Именно замена, а не досыпание: правка разметки — это новый её состав,
     * и снятая метка должна исчезать. Разница видна на удалении последнего
     * Метода — при досыпании оно было бы невозможно, а отказывать в нём должна
     * проверка состава, а не устройство запроса.
     */
    public void replaceMarkup(ProblemId id,
                              List<TaxonomyNodeId> topics,
                              List<SolutionMethodId> methods,
                              List<CharacteristicId> characteristics) {
        deleteMarkup(id);
        writeMarkup(id, topics, methods, characteristics);
    }

    /**
     * Заменяет метку Темы {@code from} на {@code to} у всех Задач, где она
     * стоит, — то, чем дерево перевешивает Задачи при перестройке
     * ({@code rubricator-restructure}).
     *
     * <p>Меняется <b>одна метка</b> в списке Тем Задачи, а не вся разметка:
     * прочие Темы, Методы и Характеристики остаются на месте. Замена целиком
     * через {@link #replaceMarkup} потребовала бы перечитать каждую Задачу
     * и переписать три таблицы ради одной строки в одной.
     *
     * <p>Задача, размеченная и {@code from}, и {@code to}, после замены несёт
     * {@code to} <b>один раз</b>: метка {@code from} у неё снимается, а не
     * переписывается — составной первичный ключ второй такой же строки
     * не примет, и Администратор получил бы ошибку базы посреди перестройки
     * дерева. Снятие идёт первым, замена — вторым: в обратном порядке дубль
     * успел бы возникнуть.
     */
    public void replaceTopic(TaxonomyNodeId from, TaxonomyNodeId to) {
        database.sql("""
                        delete from problem_topic pt
                        where pt.topic_id = ?
                          and exists (select 1 from problem_topic t
                                      where t.problem_id = pt.problem_id and t.topic_id = ?)
                        """)
                .params(from.value(), to.value())
                .update();
        database.sql("update problem_topic set topic_id = ? where topic_id = ?")
                .params(to.value(), from.value())
                .update();
    }

    /**
     * То же, что {@link #replaceTopic}, но у одной названной Задачи — для
     * поштучного распределения при снятии Темы (ADR-0007): каждой Задаче
     * свой приёмник. Соседние по Теме Задачи не задеваются.
     *
     * <p>Задача, у которой метки {@code from} нет, не меняется и отказа
     * не вызывает: полноту распределения проверяет сервис, а не запрос.
     */
    public void replaceTopicFor(ProblemId problem, TaxonomyNodeId from, TaxonomyNodeId to) {
        database.sql("""
                        delete from problem_topic pt
                        where pt.problem_id = ?
                          and pt.topic_id = ?
                          and exists (select 1 from problem_topic t
                                      where t.problem_id = pt.problem_id and t.topic_id = ?)
                        """)
                .params(problem.value(), from.value(), to.value())
                .update();
        database.sql("update problem_topic set topic_id = ? where problem_id = ? and topic_id = ?")
                .params(to.value(), problem.value(), from.value())
                .update();
    }

    /**
     * Снимает Задачу вместе со связями.
     *
     * Связи снимаются первыми: каскада по внешним ключам нет намеренно —
     * каскад унёс бы разметку и при удалении Темы или записи словаря, то есть
     * ровно в тех случаях, которым сервис обязан отказать.
     */
    public void delete(ProblemId id) {
        deleteMarkup(id);
        database.sql("delete from problem where id = ?")
                .param(id.value())
                .update();
    }

    private void deleteMarkup(ProblemId id) {
        database.sql("delete from problem_topic where problem_id = ?").param(id.value()).update();
        database.sql("delete from problem_solution_method where problem_id = ?").param(id.value()).update();
        database.sql("delete from problem_characteristic where problem_id = ?").param(id.value()).update();
    }

    private void writeMarkup(ProblemId id,
                             List<TaxonomyNodeId> topics,
                             List<SolutionMethodId> methods,
                             List<CharacteristicId> characteristics) {
        for (TaxonomyNodeId topic : distinct(topics)) {
            database.sql("insert into problem_topic (problem_id, topic_id) values (?, ?)")
                    .params(id.value(), topic.value())
                    .update();
        }
        for (SolutionMethodId method : distinct(methods)) {
            database.sql("insert into problem_solution_method (problem_id, solution_method_id) values (?, ?)")
                    .params(id.value(), method.value())
                    .update();
        }
        for (CharacteristicId characteristic : distinct(characteristics)) {
            database.sql("insert into problem_characteristic (problem_id, characteristic_id) values (?, ?)")
                    .params(id.value(), characteristic.value())
                    .update();
        }
    }

    /**
     * Дважды указанная метка — это одна метка, а не ошибка: форма разметки
     * вполне может прислать её дважды. Составной первичный ключ на такой
     * вставке упал бы, и Администратор получил бы ошибку базы вместо
     * сохранённой Задачи.
     */
    private static <T> List<T> distinct(List<T> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    private Problem withMarkup(Row row) {
        return new Problem(
                row.id(),
                row.part(),
                row.conditionFile(),
                row.solutionFile(),
                row.text(),
                topicsOf(row.id()),
                methodsOf(row.id()),
                characteristicsOf(row.id()));
    }

    /**
     * Разметка целого списка Задач — тремя запросами на весь список,
     * а не тремя на каждую Задачу.
     *
     * Так сделано только здесь, у поиска, и это не украшение: поиск без
     * условий возвращает всю библиотеку, и дочитывание по одной означало бы
     * три сотни запросов на сотне Задач — растущих ровно там, где список
     * и должен быть длинным. Одиночные {@link #findById} и
     * {@link #findByTopic} остались с прежним способом: у первого Задача
     * одна, у второго список ограничен одной Темой.
     *
     * Порядок Задач сохраняется тот, в котором их вернул отбор.
     */
    private List<Problem> withMarkup(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(row -> row.id().value()).toList();
        Map<Long, List<TaxonomyNodeId>> topics =
                grouped(ids, "problem_topic", null, "topic_id", "topic_id", TaxonomyNodeId::new);
        Map<Long, List<SolutionMethodId>> methods =
                grouped(ids, "problem_solution_method", "solution_method", "solution_method_id",
                        "solution_method_id", SolutionMethodId::new);
        Map<Long, List<CharacteristicId>> characteristics =
                grouped(ids, "problem_characteristic", "characteristic", "characteristic_id",
                        "characteristic_id", CharacteristicId::new);

        return rows.stream()
                .map(row -> new Problem(
                        row.id(),
                        row.part(),
                        row.conditionFile(),
                        row.solutionFile(),
                        row.text(),
                        topics.getOrDefault(row.id().value(), List.of()),
                        methods.getOrDefault(row.id().value(), List.of()),
                        characteristics.getOrDefault(row.id().value(), List.of())))
                .toList();
    }

    /**
     * Один вид разметки для всех перечисленных Задач, разложенный по ним.
     *
     * Порядок внутри Задачи тот же, что и у поштучного чтения: записи
     * словарей — по имени без учёта регистра, узлы — по идентификатору.
     * Разойдись он, одна и та же Задача выглядела бы по-разному в списке
     * и на своей странице.
     *
     * @param named таблица словаря для сортировки по имени; {@code null} —
     *              сортировать по самому идентификатору
     */
    private <T> Map<Long, List<T>> grouped(List<Long> ids,
                                           String table,
                                           String named,
                                           String column,
                                           String order,
                                           LongFunction<T> wrap) {
        String sql = named == null
                ? "select l.problem_id, l.%s from %s l where l.problem_id in (%s) order by l.%s"
                        .formatted(column, table, placeholders(ids.size()), order)
                : "select l.problem_id, l.%s from %s l join %s d on d.id = l.%s where l.problem_id in (%s) order by lower(d.name)"
                        .formatted(column, table, named, column, placeholders(ids.size()));

        Map<Long, List<T>> byProblem = new LinkedHashMap<>();
        database.sql(sql)
                .params(new ArrayList<Object>(ids))
                .query((rs, rowNum) -> Map.entry(rs.getLong("problem_id"), wrap.apply(rs.getLong(column))))
                .list()
                .forEach(entry -> byProblem.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byProblem;
    }

    private List<TaxonomyNodeId> topicsOf(ProblemId id) {
        return database.sql("select topic_id from problem_topic where problem_id = ? order by topic_id")
                .param(id.value())
                .query((rs, rowNum) -> new TaxonomyNodeId(rs.getLong("topic_id")))
                .list();
    }

    private List<SolutionMethodId> methodsOf(ProblemId id) {
        return database.sql("""
                        select psm.solution_method_id
                        from problem_solution_method psm
                        join solution_method m on m.id = psm.solution_method_id
                        where psm.problem_id = ?
                        order by lower(m.name)
                        """)
                .param(id.value())
                .query((rs, rowNum) -> new SolutionMethodId(rs.getLong("solution_method_id")))
                .list();
    }

    private List<CharacteristicId> characteristicsOf(ProblemId id) {
        return database.sql("""
                        select pc.characteristic_id
                        from problem_characteristic pc
                        join characteristic c on c.id = pc.characteristic_id
                        where pc.problem_id = ?
                        order by lower(c.name)
                        """)
                .param(id.value())
                .query((rs, rowNum) -> new CharacteristicId(rs.getLong("characteristic_id")))
                .list();
    }

    /**
     * Строка таблицы {@code problem} без разметки.
     *
     * Отдельный тип, а не {@link Problem} с пустыми списками: пустые списки
     * запись не примет — «хотя бы одна Тема» проверяется её конструктором, —
     * а подставленные заглушки означали бы, что Задача с выдуманной разметкой
     * может уйти наружу, если дочитать разметку однажды забудут.
     */
    private record Row(ProblemId id, ExamPart part, FileKey conditionFile, FileKey solutionFile, ProblemText text) {
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        String condition = rs.getString("condition_text");
        ProblemText text = condition == null ? null : new ProblemText(
                condition,
                rs.getString("solution_text"),
                keyOf(rs.getString("condition_evidence_key")),
                keyOf(rs.getString("solution_evidence_key")),
                rs.getInt("text_version"));
        return new Row(
                new ProblemId(rs.getLong("id")),
                ExamPart.valueOf(rs.getString("exam_part")),
                new FileKey(rs.getString("condition_file_key")),
                new FileKey(rs.getString("solution_file_key")),
                text);
    }

    private static FileKey keyOf(String value) {
        return value == null ? null : new FileKey(value);
    }
}
