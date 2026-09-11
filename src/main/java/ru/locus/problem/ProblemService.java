package ru.locus.problem;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.dictionary.Characteristic;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethod;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.taxonomy.TaxonomyNode;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyPath;
import ru.locus.taxonomy.TaxonomyService;

/**
 * Ведение Задач: чтение библиотеки и правила заведения, разметки и правки.
 *
 * Права проверяются здесь, а не в контроллере и не на адресах (standards.md,
 * «Слои и границы»): правило привязано к операции и срабатывает при любом
 * способе вызова. Ведёт Задачи только Администратор; читают их все вошедшие —
 * вход уже потребован грубым рубежом в {@code SecurityConfig}, а роли для
 * чтения общей библиотеки не важны.
 *
 * Задачи — общая библиотека, и по владельцу они <b>не фильтруются</b>: все
 * Учителя видят одни и те же Задачи (ADR-0004, ADR-0027). Параметра владельца
 * нет ни здесь, ни в репозитории — без него выдача заданий была бы
 * невозможна.
 *
 * Файлы кладутся и отдаются только через {@link FileStorage}: SDK хранилища
 * здесь не появляется, а наружу файл уходит исключительно временной
 * подписанной ссылкой (standards.md, «Файлы»).
 */
@Service
public class ProblemService {

    private final ProblemRepository problems;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;
    private final FileStorage storage;

    /**
     * Реализации вопроса «использована ли Задача». Сегодня список пуст —
     * ни Заданий, ни Работ не существует; подробности и долг —
     * в {@link ProblemUsage}.
     */
    private final List<ProblemUsage> usages;

    public ProblemService(ProblemRepository problems,
                          TaxonomyService taxonomy,
                          SolutionMethodService methods,
                          CharacteristicService characteristics,
                          FileStorage storage,
                          List<ProblemUsage> usages) {
        this.problems = problems;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
        this.storage = storage;
        this.usages = usages;
    }

    public Problem problem(ProblemId id) {
        return existing(id);
    }

    /** Задачи, размеченные этой Темой, по номеру. */
    public List<Problem> problemsOf(TaxonomyNodeId topic) {
        return problems.findByTopic(topic);
    }

    /**
     * Поиск Задач по разметке: узел рубрикатора, Методы, Характеристики,
     * Часть (ADR-0031).
     *
     * <p>Узел разворачивается в список Тем поддерева обходом рубрикатора —
     * {@link TaxonomyService#subtree}. Своего обхода здесь <b>не заводится</b>,
     * и это не вопрос вкуса: второй обход завёл бы второй предел глубины,
     * а разошлись бы они молча, в глубоком дереве, потеряв узлы
     * (standards.md, «Данные»). Заодно оттуда же берётся отказ
     * на несуществующем узле — искать по узлу, которого нет, нельзя,
     * а пустой результат был бы правдоподобным и неотличимым от честного
     * «ничего не нашлось».
     *
     * <p>Остальные условия уходят в репозиторий как есть: разбирается их
     * пустота там, в одном месте на все три измерения
     * ({@link ProblemRepository#search}).
     *
     * <p>Без {@code @PreAuthorize} — как и остальное чтение общей библиотеки:
     * вход уже потребован грубым рубежом {@code SecurityConfig}, а роль
     * Администратора запретила бы поиск Учителю, то есть тому, ради кого
     * он делается. Фильтра по владельцу здесь нет и быть не может: Задачи
     * общие (ADR-0027).
     */
    public List<FoundProblem> search(ProblemFilter filter) {
        List<TaxonomyNodeId> topics = filter.node() == null ? null : subtreeOf(filter.node());
        List<Problem> found = problems.search(topics,
                filter.methods(), filter.methodMode(),
                filter.characteristics(), filter.characteristicMode(),
                filter.part());
        return named(found);
    }

    /** Узел и всё его поддерево — идентификаторами, как их ждёт отбор. */
    private List<TaxonomyNodeId> subtreeOf(TaxonomyNodeId node) {
        return taxonomy.subtree(node).stream().map(TaxonomyNode::id).toList();
    }

    /**
     * Разметка найденных Задач, названная по-человечески.
     *
     * <p>Пути и оба словаря читаются <b>по одному разу</b> и складываются
     * в отображения «идентификатор → имя». Приём
     * {@link ProblemController#problem}, где имена получаются фильтрацией
     * полного списка по каждой метке, здесь не годится: на одной Задаче он
     * незаметен, на списке даёт перебор «все найденные × весь словарь».
     *
     * <p>На пустом результате словари не читаются вовсе: {@code paths()}
     * строит всё дерево, и платить за это, когда называть нечего, незачем.
     */
    private List<FoundProblem> named(List<Problem> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<TaxonomyNodeId, String> topicNames = taxonomy.paths().stream()
                .collect(Collectors.toMap(TaxonomyPath::id, TaxonomyPath::path));
        Map<SolutionMethodId, String> methodNames = methods.all().stream()
                .collect(Collectors.toMap(SolutionMethod::id, SolutionMethod::name));
        Map<CharacteristicId, String> characteristicNames = characteristics.all().stream()
                .collect(Collectors.toMap(Characteristic::id, Characteristic::name));
        return found.stream()
                .map(problem -> new FoundProblem(problem,
                        names(problem.topics(), topicNames, "узел рубрикатора"),
                        names(problem.methods(), methodNames, "Метод"),
                        names(problem.characteristics(), characteristicNames, "Характеристику")))
                .toList();
    }

    /**
     * Имена по идентификаторам разметки.
     *
     * Метка без имени — испорченные данные, и молчать о них нельзя: внешние
     * ключи такого не допускают, а пропущенная строка в списке выглядела бы
     * как Задача, размеченная беднее, чем она размечена.
     */
    private static <T> List<String> names(List<T> ids, Map<T, String> names, String what) {
        return ids.stream()
                .map(id -> {
                    String name = names.get(id);
                    if (name == null) {
                        throw new IllegalStateException(
                                "Разметка Задачи ссылается на " + what + ", которого нет: " + id);
                    }
                    return name;
                })
                .toList();
    }

    /**
     * Методы, уже встречавшиеся в Задачах этой Темы, — то, что форма разметки
     * показывает первым.
     *
     * Выбор этим не ограничивается: полный словарь остаётся доступен, и Метод,
     * в Теме не встречавшийся, разрешено указать (ADR-0009, ADR-0010).
     */
    public List<SolutionMethod> methodsUsedIn(TaxonomyNodeId topic) {
        return problems.findMethodsUsedInTopic(topic);
    }

    /**
     * Временная подписанная ссылка на PDF условия.
     *
     * Ключ файла наружу не отдаётся ни здесь, ни где-либо ещё: постоянного
     * адреса у файла Задачи нет, а PDF с решениями не должны попадать
     * к ученикам (ADR-0021, antipatterns.md, «Публичный бакет или постоянная
     * ссылка»).
     */
    public URI conditionLink(ProblemId id) {
        return storage.temporaryLink(existing(id).conditionFile());
    }

    /** Временная подписанная ссылка на PDF решения — тем же правилом. */
    public URI solutionLink(ProblemId id) {
        return storage.temporaryLink(existing(id).solutionFile());
    }

    /**
     * Заводит Задачу: оба файла и разметка.
     *
     * <p>Порядок работы с хранилищем и базой выбран сознательно: сначала оба
     * файла кладутся, затем сохраняется запись, а если сохранение не удалось —
     * только что положенные файлы убираются. Общей транзакции у базы
     * и хранилища нет и быть не может, поэтому выбирается меньшее из зол:
     * забытый файл не адресуется ниоткуда и никому не мешает, а Задача
     * без файлов была бы видна в списке, открывалась и выглядела настоящей
     * (design.md, «Порядок укладки файлов»).
     *
     * <p>Проверки идут до укладки: отказ не должен оставлять в хранилище
     * ничего.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public ProblemId create(String caption,
                            ExamPart part,
                            List<TaxonomyNodeId> topics,
                            List<SolutionMethodId> methodIds,
                            List<CharacteristicId> characteristicIds,
                            UploadedFile condition,
                            UploadedFile solution) {
        Problem.requireMarkup(part, topics, methodIds);
        requireExistingMarkup(topics, methodIds, characteristicIds);
        requireFile(condition, "условия");
        requireFile(solution, "решения");

        FileKey conditionKey = storage.put(condition.content(), condition.contentType());
        FileKey solutionKey = null;
        try {
            solutionKey = storage.put(solution.content(), solution.contentType());
            return problems.create(caption, part, conditionKey, solutionKey,
                    topics, methodIds, characteristicIds);
        } catch (RuntimeException failure) {
            discard(conditionKey);
            discard(solutionKey);
            throw failure;
        }
    }

    /**
     * Меняет подпись и разметку Задачи. Номер при этом не меняется — он и есть
     * идентификатор Задачи и человеку обещан неизменным (ADR-0029).
     *
     * Правка не снимает обязательности состава: Задача без Темы или без Метода
     * не сохраняется и правкой тоже.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void edit(ProblemId id,
                     String caption,
                     ExamPart part,
                     List<TaxonomyNodeId> topics,
                     List<SolutionMethodId> methodIds,
                     List<CharacteristicId> characteristicIds) {
        Problem problem = existing(id);
        refuseUnlessUnused(problem);
        Problem.requireMarkup(part, topics, methodIds);
        requireExistingMarkup(topics, methodIds, characteristicIds);

        problems.changeCaption(problem.id(), caption);
        problems.changePart(problem.id(), part);
        problems.replaceMarkup(problem.id(), topics, methodIds, characteristicIds);
    }

    /** Заменяет PDF условия. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void replaceCondition(ProblemId id, UploadedFile condition) {
        Problem problem = existing(id);
        refuseUnlessUnused(problem);
        requireFile(condition, "условия");

        FileKey replacement = storage.put(condition.content(), condition.contentType());
        problems.changeConditionFile(problem.id(), replacement);
        storage.delete(problem.conditionFile());
    }

    /**
     * Заменяет PDF решения.
     *
     * Порядок тот же, что и у условия, и он важен: положить новый, переписать
     * ключ, удалить старый. Удаление старого идёт последним — потерять новый
     * файл хуже, чем оставить прежний.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void replaceSolution(ProblemId id, UploadedFile solution) {
        Problem problem = existing(id);
        refuseUnlessUnused(problem);
        requireFile(solution, "решения");

        FileKey replacement = storage.put(solution.content(), solution.contentType());
        problems.changeSolutionFile(problem.id(), replacement);
        storage.delete(problem.solutionFile());
    }

    /**
     * Снимает Задачу вместе с разметкой и обоими файлами.
     *
     * Файлы уносятся потому, что кроме кода Задачи распознать ненужный файл
     * некому: хранилище не знает, что лежит по ключу, а оставленный файл уже
     * ничем не адресуется — отличить его от нужного впоследствии невозможно.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void delete(ProblemId id) {
        Problem problem = existing(id);
        refuseUnlessUnused(problem);

        problems.delete(problem.id());
        storage.delete(problem.conditionFile());
        storage.delete(problem.solutionFile());
    }

    /**
     * Перевешивает все Задачи Темы {@code from} на Тему-приёмник {@code to} —
     * так дерево углубляет Тему с содержимым (ADR-0007): у Темы появляется
     * потомок, а её Задачи переезжают на приёмник, указанный Администратором.
     *
     * <p>{@link #refuseUnlessUnused} здесь <b>не зовётся, и это не пропуск</b>
     * (ADR-0034). Заморожена правка рукой — та, где Администратор сам выбирает
     * Задаче разметку. Перестройка не правит разметку, а двигает ось, вдоль
     * которой разметка задана: Задача остаётся на том же месте предметно,
     * меняется имя и положение узла. Позови проверку здесь — и первая же
     * выданная Задача заморозила бы не себя, а дерево: Тему с ней нельзя было
     * бы ни углубить, ни снять, навсегда, потому что Задания не отменяются.
     *
     * <p>Поэтому это <b>отдельный, названный</b> путь изменения разметки, а не
     * {@link #edit} с флагом «не проверять»: флаг однажды оказался бы
     * выставлен из формы правки, и заморозка перестала бы работать молча
     * (design.md, «Второй путь правки разметки закрыт от случайного вызова»).
     * Отсутствие вызова сторожит {@code RestructureBypassesFreezeTest}.
     *
     * <p>Что приёмник — Тема, здесь не проверяется: вид узла не хранится,
     * а считается по потомкам, и в момент вызова дерево ещё меняется —
     * углубляемая Тема уже получила потомка, приёмник же может быть этим
     * самым потомком. Каким дерево станет, знает только
     * {@link TaxonomyService}, и проверка стоит там (design.md, «Проверка
     * „приёмник — Тема“ считает состояние после операции»). Существование
     * приёмника проверяется: отказ «узла нет» внятнее ошибки внешнего ключа.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void rehomeTopic(TaxonomyNodeId from, TaxonomyNodeId to) {
        taxonomy.node(to);
        problems.replaceTopic(from, to);
    }

    /**
     * Распределяет Задачи Темы {@code from} поштучно: каждой — своя
     * Тема-приёмник. Так дерево снимает Тему с содержимым (ADR-0007).
     *
     * <p>Карта принимается <b>целиком</b> и проверяется на полноту до первой
     * правки: каждая Задача Темы обязана быть названа, посторонних быть
     * не должно. Неполная или лишняя карта отклоняет операцию целиком —
     * прерванный на середине цикл оставил бы Тему, часть Задач которой уже
     * уехала, и продолжать пришлось бы вручную, гадая, где остановились
     * (design.md, «Поштучное распределение принимает карту, а не список»).
     *
     * <p>{@link #refuseUnlessUnused} не зовётся по той же причине, что
     * и в {@link #rehomeTopic}: перестройка сильнее заморозки (ADR-0034).
     * Проверка «приёмник — Тема» — тоже там же, где и у углубления: в дереве.
     *
     * @throws IllegalArgumentException если карта неполна или содержит
     *                                  Задачу, на Теме не размеченную
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void distribute(TaxonomyNodeId from, Map<ProblemId, TaxonomyNodeId> destinations) {
        Set<ProblemId> onTopic = problems.findByTopic(from).stream()
                .map(Problem::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ProblemId> missing = onTopic.stream()
                .filter(id -> !destinations.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Распределение неполно: не названы Задачи № "
                    + numbers(missing) + " — распределить Тему можно только целиком");
        }
        List<ProblemId> strangers = destinations.keySet().stream()
                .filter(id -> !onTopic.contains(id))
                .toList();
        if (!strangers.isEmpty()) {
            throw new IllegalArgumentException("В распределении названы Задачи, на Теме не размеченные: № "
                    + numbers(strangers));
        }
        destinations.values().forEach(taxonomy::node);

        destinations.forEach((problem, to) -> problems.replaceTopicFor(problem, from, to));
    }

    private static String numbers(List<ProblemId> ids) {
        return ids.stream().map(id -> String.valueOf(id.value())).collect(Collectors.joining(", "));
    }

    /**
     * Единственная проверка того, что Задача ещё не использована: сюда
     * дописывается каждое новое условие, и искать его потом надо в одном
     * месте, а не по всем вызовам правки и удаления.
     *
     * <p>Сегодня условие выполняется тождественно: реализаций
     * {@link ProblemUsage} нет ни одной — ни Заданий, ни Работ в системе
     * не существует. Это не заглушка «на будущее», а честное состояние,
     * и оно временное. <b>Появление каждой из двух сущностей обязано
     * пополнить эту проверку</b>:
     *
     * <ul>
     *   <li>Задание — Задача вошла в чью-то выдачу; работа
     *       {@code assignments};</li>
     *   <li>Работа — по Задаче принято решение ученика; работа
     *       {@code submission-review}.</li>
     * </ul>
     *
     * <p>Забытое пополнение — тихая порча чужих данных: правит Задачу
     * Администратор, а он не видит ни чужих Заданий, ни чужих Работ, ни чужих
     * отметок. Правка разметки выданной Задачи меняет основание ячеек владения
     * задним числом, удаление — стирает у чужого учителя смысл его Работы,
     * и уведомлений система не шлёт (ADR-0030, ADR-0017).
     */
    private void refuseUnlessUnused(Problem problem) {
        for (ProblemUsage usage : usages) {
            Optional<String> used = usage.of(problem.id());
            if (used.isPresent()) {
                throw new ProblemInUseException("Задача № " + problem.number() + " уже используется ("
                        + used.get() + "): изменить и удалить её нельзя");
            }
        }
    }

    /**
     * Проверка инварианта 1: Задача висит только на Теме — листе дерева.
     *
     * Внешним ключом это не выражается: отдельной таблицы Тем нет и быть
     * не может, вид узла не хранится, а вычисляется по наличию потомков
     * (design.md, «Схема»). Заодно проверяется, что узел вообще существует, —
     * {@link TaxonomyService#node} отказывает на отсутствующем.
     */
    private void requireExistingMarkup(List<TaxonomyNodeId> topics,
                                       List<SolutionMethodId> methodIds,
                                       List<CharacteristicId> characteristicIds) {
        for (TaxonomyNodeId topic : topics) {
            TaxonomyNode node = taxonomy.node(topic);
            if (!node.isTopic()) {
                throw new NotATopicException("Узел «" + node.name()
                        + "» — Раздел: Задачи несут только Темы, то есть узлы без потомков");
            }
        }
        // Отсутствующая запись словаря — тоже отказ, и внятный: внешний ключ
        // дал бы исключение драйвера, всплывшее из репозитория.
        methodIds.forEach(methods::method);
        if (characteristicIds != null) {
            characteristicIds.forEach(characteristics::characteristic);
        }
    }

    /**
     * Оба PDF обязательны, и отказ называет недостающий: «файл не приложен»
     * без уточнения заставляет Администратора гадать, какой из двух.
     */
    private static void requireFile(UploadedFile file, String what) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Не приложен PDF " + what);
        }
    }

    /**
     * Уборка файла, положенного в хранилище перед неудавшейся записью.
     *
     * Сбой самой уборки не должен подменять собой исходную ошибку: тот,
     * кто заводил Задачу, должен увидеть, почему она не завелась, а не почему
     * не удалось убрать за собой. Оставшийся файл при этом никому не мешает —
     * он не адресуется ниоткуда.
     */
    private void discard(FileKey key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Исходная ошибка важнее; см. пояснение выше.
        }
    }

    private Problem existing(ProblemId id) {
        return problems.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "Задачи с номером " + id.value() + " не существует"));
    }
}
