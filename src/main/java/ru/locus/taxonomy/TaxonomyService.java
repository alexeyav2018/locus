package ru.locus.taxonomy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ведение рубрикатора: чтение дерева и правила его перестройки.
 *
 * Права проверяются здесь, а не в контроллере и не на адресах (standards.md,
 * «Слои и границы»): правило привязано к операции и срабатывает при любом
 * способе вызова. Правит дерево только Администратор; читают его все
 * вошедшие — вход уже потребован грубым рубежом в {@code SecurityConfig},
 * а роли для чтения общей библиотеки не важны.
 *
 * Рубрикатор — общая библиотека, и по владельцу он <b>не фильтруется</b>:
 * все Учителя видят одно и то же дерево (ADR-0004, ADR-0027). Параметра
 * владельца нет ни здесь, ни в репозитории.
 */
@Service
public class TaxonomyService {

    private final TaxonomyRepository nodes;

    /**
     * Области, что-то на узлы вешающие, — источник ответа на вопрос «что лежит
     * на этом узле». Собираются списком: дерево не знает ни одной из них
     * по имени, и появление следующей его не касается.
     */
    private final List<NodeContent> content;

    public TaxonomyService(TaxonomyRepository nodes, List<NodeContent> content) {
        this.nodes = nodes;
        this.content = content;
    }

    /** Всё дерево, собранное от корней; братья — по алфавиту. */
    public List<TaxonomyBranch> tree() {
        List<TaxonomyBranch> tree = new ArrayList<>();
        for (TaxonomyNode root : nodes.findRoots()) {
            tree.add(assemble(root, byParent(nodes.findSubtree(root.id()))));
        }
        return List.copyOf(tree);
    }

    /** Корневые узлы. */
    public List<TaxonomyNode> roots() {
        return nodes.findRoots();
    }

    /** Прямые потомки узла. */
    public List<TaxonomyNode> children(TaxonomyNodeId parent) {
        return nodes.findChildren(existing(parent).id());
    }

    public TaxonomyNode node(TaxonomyNodeId id) {
        return existing(id);
    }

    /**
     * Узел и все его потомки на любой глубине, включая сам узел.
     *
     * На этом обходе держится правило «поиск по узлу включает всё поддерево»
     * (инвариант 10): без него чем глубже размечено дерево, тем меньше
     * находится по верхним узлам.
     */
    public List<TaxonomyNode> subtree(TaxonomyNodeId root) {
        return nodes.findSubtree(existing(root).id());
    }

    /** Все узлы дерева, названные полным путём, — для выбора родителя. */
    public List<TaxonomyPath> paths() {
        List<TaxonomyPath> paths = new ArrayList<>();
        tree().forEach(branch -> collectPaths(branch, "", paths));
        return List.copyOf(paths);
    }

    /**
     * Пути только до Тем — листьев дерева. То, из чего выбирают Тему разметки.
     *
     * Отдельный метод, а не отсев в шаблоне или в контроллере: «узел — лист»
     * вычисляется из числа потомков, и вычисление это предметное. Уехав
     * в шаблон, оно разъехалось бы с проверкой в сервисе Задачи — и форма
     * предлагала бы Раздел, на который Задачу всё равно не привязать
     * (инвариант 1).
     */
    public List<TaxonomyPath> topicPaths() {
        return paths().stream()
                .filter(path -> node(path.id()).isTopic())
                .toList();
    }

    /**
     * Узел и все его предки — от корня до запрошенного включительно.
     *
     * Обратная сторона {@link #subtree}: там спуск, здесь подъём. На этой
     * цепочке держится наследование Теоретических материалов вниз по дереву
     * (ADR-0032): материал Раздела виден на каждой Теме внутри него.
     *
     * Цепочка собирается подъёмом по родителям, а не рекурсивным запросом:
     * ради одного узла обходить дерево незачем, а глубина измеряется
     * единицами уровней. Подъём ограничен по числу шагов — по той же причине,
     * что и рекурсивный спуск: испорченные данные должны давать ошибку,
     * а не вечный цикл.
     */
    public List<TaxonomyNode> ancestry(TaxonomyNodeId id) {
        Deque<TaxonomyNode> chain = new ArrayDeque<>();
        TaxonomyNode current = existing(id);
        for (int step = 0; step < TaxonomyRepository.MAX_DEPTH; step++) {
            chain.addFirst(current);
            TaxonomyNodeId parent = current.parent();
            if (parent == null) {
                return List.copyOf(chain);
            }
            current = existing(parent);
        }
        throw new IllegalStateException("Подъём от узла " + id.value() + " к корню достиг предела глубины "
                + TaxonomyRepository.MAX_DEPTH + ": похоже на цикл в дереве");
    }

    /**
     * Узел, названный полным путём от корня, — то, что показывает правая
     * панель экрана.
     *
     * Подъём здесь не свой: путь — это имена узлов из {@link #ancestry},
     * склеенные разделителем. Второй копии подъёма в проекте нет и быть
     * не должно — разойдясь, копии теряют узлы молча, и стережёт это
     * {@code AncestryWalkIsNotDuplicatedTest}.
     */
    public TaxonomyPath path(TaxonomyNodeId id) {
        List<TaxonomyNode> chain = ancestry(id);
        List<String> names = chain.stream().map(TaxonomyNode::name).toList();
        return new TaxonomyPath(chain.getLast().id(), String.join(" / ", names));
    }

    /**
     * Заводит узел. {@code parent} {@code null} — узел становится корневым.
     *
     * Вид узла при этом не назначается и нигде не сохраняется: новый узел
     * потомков не имеет, значит он Тема; появится потомок — станет Разделом
     * сам собой. Ровно поэтому родителю, несущему Задачи, потомка без
     * Темы-приёмника не добавить — см. {@link #create(String, TaxonomyNodeId,
     * TopicReceiver)}.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public TaxonomyNodeId create(String name, TaxonomyNodeId parent) {
        return create(name, parent, null);
    }

    /**
     * Заводит узел, а если родитель — Тема с содержимым, требующим Темы,
     * перевешивает это содержимое на {@code receiver} той же операцией
     * (ADR-0007). Разделять их нельзя: Тема, оставшаяся Разделом с Задачами
     * хотя бы на мгновение видимого состояния, нарушает инвариант 1.
     *
     * <p>Порядок шагов не случаен. Сначала все проверки, потом создание
     * потомка, и только потом переезд: приёмником может быть сам создаваемый
     * потомок, а идентификатор у него появляется лишь при создании. Отказ
     * на любом шаге откатывает транзакцию целиком — узла нет, содержимое
     * на месте.
     *
     * <p>Что именно переезжает и из какой области, дерево по-прежнему
     * не знает: оно просит каждого ответчика {@link NodeContent} убрать
     * за собой ({@link NodeContent#moveTopicContent}). Переезд просится
     * у всех, как только приёмник указан, — даже если содержимого нет:
     * ответчику, которому переезжать нечего, вызов безвреден, а проверять
     * дважды одно и то же незачем.
     *
     * @param receiver Тема-приёмник; {@code null} — не указана. Без неё
     *                 родитель с содержимым, требующим Темы, потомка
     *                 не получает. Указанная проверяется всегда, и без
     *                 содержимого тоже: неверный приёмник — неверный ввод,
     *                 а не безобидная деталь.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public TaxonomyNodeId create(String name, TaxonomyNodeId parent, TopicReceiver receiver) {
        String trimmed = requireName(name);
        if (parent == null) {
            if (receiver != null) {
                throw new IllegalArgumentException("Тема-приёмник указывается только при углублении"
                        + " существующего узла: у корня переезжать нечему");
            }
        } else {
            TaxonomyNode deepened = existing(parent);
            if (receiver == null) {
                refuseIfDeepeningLosesContent(deepened);
            } else {
                refuseUnlessReceiverStaysATopic(deepened, receiver);
            }
        }
        refuseIfNameTaken(parent, trimmed, null);
        TaxonomyNodeId created = nodes.create(trimmed, parent);
        if (receiver != null) {
            TaxonomyNodeId target = receiver instanceof TopicReceiver.Existing existing
                    ? existing.id()
                    : created;
            for (NodeContent kind : content) {
                kind.moveTopicContent(parent, target);
            }
        }
        return created;
    }

    /**
     * Меняет имя узла. Положение узла в дереве, его потомки и его
     * идентификатор при этом не меняются (ADR-0007).
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void rename(TaxonomyNodeId id, String newName) {
        TaxonomyNode node = existing(id);
        String trimmed = requireName(newName);
        refuseIfNameTaken(node.parent(), trimmed, node.id());
        nodes.rename(node.id(), trimmed);
    }

    /**
     * Переносит узел под другого родителя; {@code newParent} {@code null} —
     * поднимает узел в корень. Узел переезжает вместе со всем поддеревом:
     * меняется одна связь «родитель», взаимное расположение потомков
     * не трогается.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void move(TaxonomyNodeId id, TaxonomyNodeId newParent) {
        TaxonomyNode node = existing(id);
        if (newParent != null) {
            refuseIfInsideOwnSubtree(node, existing(newParent));
        }
        refuseIfNameTaken(newParent, node.name(), node.id());
        nodes.changeParent(node.id(), newParent);
    }

    /** Снимает узел, если он пуст. Непустой узел не удаляется. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void delete(TaxonomyNodeId id) {
        TaxonomyNode node = existing(id);
        refuseUnlessEmpty(node);
        nodes.delete(node.id());
    }

    /**
     * Сколько отметок Владения исчезнет невосполнимо вместе с узлом — то, что
     * показывается Администратору перед снятием Темы с распределением
     * (ADR-0007). Сумма ответов всех {@link NodeContent#countVanishing}.
     *
     * <p>Это <b>единственное</b> место, где Администратору видны данные
     * личного контура чужих учителей, и потому единственное, что по владельцу
     * не фильтруется намеренно (ADR-0005, ADR-0027): считаются отметки всех
     * учеников всех учителей. Наружу — только число; параметра владельца
     * у метода нет, и показать лишнее ему нечем. По той же причине спрашивать
     * может только Администратор: Учителю чужие отметки не видны даже счётом.
     *
     * <p>Сегодня всегда ноль — отвечающего по существу нет; долг
     * {@code mastery-marks} сторожит {@code MasteryRestructureDebtTest}.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public int countVanishingMarks(TaxonomyNodeId id) {
        TaxonomyNode node = existing(id);
        int vanishing = 0;
        for (NodeContent kind : content) {
            vanishing += kind.countVanishing(node.id());
        }
        return vanishing;
    }

    /**
     * Снимает Тему с содержимым, распределив содержимое по Темам-приёмникам,
     * назначенным Администратором поштучно (ADR-0007). Отдельная операция,
     * а не {@link #delete} с параметром: обычное удаление снимает только
     * пустой узел, и смешивать их — значит однажды снять непустой по ошибке.
     *
     * <p>Порядок шагов:
     *
     * <ol>
     *   <li>потомки — отказ: узлы снимаются по одному, снизу вверх;</li>
     *   <li>содержимое, которому Тема не нужна (Теоретические материалы), —
     *       отказ, называющий выход: распределять его некуда, и снимает или
     *       переносит его Администратор обычной правкой, ничем
     *       не обусловленной (ADR-0033);</li>
     *   <li>каждый ответчик {@link NodeContent} распределяет своё
     *       ({@link NodeContent#distributeTopicContent}) — либо убирает,
     *       если оно исчезает вместе с Темой; после этого узел обязан быть
     *       пуст, иначе отказ;</li>
     *   <li>узел снимается;</li>
     *   <li>каждый приёмник проверяется <b>по состоянию дерева после
     *       снятия</b> (design.md, «Проверка „приёмник — Тема“ считает
     *       состояние после операции»): у него не должно быть потомков.
     *       Родитель снятой Темы этим проверяется без особого случая —
     *       если она была его единственным потомком, теперь он лист.</li>
     * </ol>
     *
     * <p>Проверка приёмников стоит <i>после</i> снятия не по недосмотру:
     * состояние «после операции» проще прочитать из дерева, чем вычислить
     * заранее, а отказ на этом шаге откатывает транзакцию целиком — узел
     * на месте, разметка прежняя. Что откат действительно целый, проверяет
     * тест целостности в {@code ProblemsGuardTheTreeTest}.
     *
     * <p>Что именно распределяется и из какой области, дерево не знает:
     * в {@code distribution} оно читает только приёмников, остальное —
     * область содержимого ({@link TopicDistribution}).
     *
     * @throws NodeNotEmptyException      если у узла есть потомки, на нём
     *                                    лежит содержимое, не подлежащее
     *                                    распределению, или после
     *                                    распределения узел не опустел
     * @throws ReceiverIsNotATopicException если приёмник — сама снимаемая
     *                                    Тема или узел, у которого после
     *                                    снятия остаются потомки
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void deleteWithDistribution(TaxonomyNodeId id, TopicDistribution distribution) {
        TaxonomyNode node = existing(id);
        refuseIfHasChildren(node);
        refuseIfCarriesUndistributable(node);
        if (distribution.receivers().contains(node.id())) {
            throw new ReceiverIsNotATopicException("Снимаемая Тема «" + node.name() + "» не может быть"
                    + " Темой-приёмником для собственного содержимого: после снятия её не будет");
        }
        for (NodeContent kind : content) {
            kind.distributeTopicContent(node.id(), distribution);
        }
        for (NodeContent kind : content) {
            Optional<String> held = kind.on(node.id());
            if (held.isPresent()) {
                throw new NodeNotEmptyException("Узел «" + node.name() + "» после распределения не опустел: "
                        + held.get());
            }
        }
        nodes.delete(node.id());
        for (TaxonomyNodeId receiver : distribution.receivers()) {
            TaxonomyNode candidate = existing(receiver);
            int children = nodes.countChildren(candidate.id());
            if (children > 0) {
                throw new ReceiverIsNotATopicException("Узел «" + candidate.name() + "» не может быть"
                        + " Темой-приёмником: после снятия «" + node.name() + "» у него остаются потомки ("
                        + children + "), а Задачи несут только Темы");
            }
        }
    }

    /**
     * Единственная проверка пустоты узла: сюда дописывается каждое новое
     * условие, и искать его потом надо в одном месте, а не по всем вызовам
     * удаления.
     *
     * <p>Условий два: у узла нет потомков и на узле нет содержимого. О втором
     * дерево спрашивает {@link NodeContent} — вопрос, на который отвечают
     * области, что-то на узлы вешающие; сам {@code TaxonomyService} ни одну
     * из них не знает по имени (design.md, «Проверки в чужих областях»).
     *
     * <p>Содержимым узла считается всё, что на нём висит, и <b>появление
     * каждой из трёх сущностей обязано пополнить эту проверку</b>:
     *
     * <ul>
     *   <li>Задачи — на Теме; <b>пришли</b> с работой {@code problem-catalog},
     *       отвечает {@code ProblemsOnNode};</li>
     *   <li>Теоретические материалы — на любом узле; <b>пришли</b> с работой
     *       {@code theory-materials}, отвечает {@code TheoryOnNode};</li>
     *   <li>отметки Владения — на паре «Тема × Метод»; работа
     *       {@code mastery-marks}.</li>
     * </ul>
     *
     * <p>Забытое пополнение — тихая потеря данных: узел уходит вместе
     * с накопленными суждениями учителей об учениках, и восстановить их
     * неоткуда (ADR-0007). Удаление Темы <i>с</i> содержимым — не эта
     * операция, а отдельная: {@link #deleteWithDistribution}, с распределением
     * Задач и предупреждением о числе исчезающих отметок
     * ({@link #countVanishingMarks}). Отказ здесь называет её как выход,
     * когда содержимое такое, что распределению подлежит, — то есть
     * отвечает на {@link NodeContent#requiringTopic}.
     */
    private void refuseUnlessEmpty(TaxonomyNode node) {
        refuseIfHasChildren(node);
        for (NodeContent kind : content) {
            Optional<String> held = kind.on(node.id());
            if (held.isPresent()) {
                String exit = kind.requiringTopic(node.id()).isPresent()
                        ? " — снять Тему вместе с этим можно только снятием с распределением"
                        : "";
                throw new NodeNotEmptyException("Узел «" + node.name() + "» не пуст: " + held.get() + exit);
            }
        }
    }

    private void refuseIfHasChildren(TaxonomyNode node) {
        int children = nodes.countChildren(node.id());
        if (children > 0) {
            throw new NodeNotEmptyException("У узла «" + node.name() + "» есть потомки (" + children
                    + "): узлы снимаются по одному, снизу вверх");
        }
    }

    /**
     * Снятию с распределением мешает содержимое, которому Тема не нужна:
     * оно законно на любом узле, распределять его некуда, и исчезать вместе
     * с узлом оно не должно. Сегодня это Теоретические материалы.
     *
     * <p>Отказ обязан назвать выход: материал снимается или переносится
     * на другой узел его обычной правкой, и никаких условий на перенос
     * не наложено — заморозки у теории нет (ADR-0033). Само слово
     * «материалы» дерево не произносит — оно приходит из ответа
     * {@link NodeContent#on}; дерево знает только, что это содержимое
     * на {@link NodeContent#requiringTopic} не отвечает.
     */
    private void refuseIfCarriesUndistributable(TaxonomyNode node) {
        for (NodeContent kind : content) {
            Optional<String> held = kind.on(node.id());
            if (held.isPresent() && kind.requiringTopic(node.id()).isEmpty()) {
                throw new NodeNotEmptyException("Узел «" + node.name() + "» нельзя снять с распределением: "
                        + held.get() + ". Это содержимое не распределяется — сначала снимите его или"
                        + " перенесите на другой узел обычной правкой; никаких условий на перенос нет");
            }
        }
    }

    /**
     * Углубить узел, несущий содержимое, которое живёт только на Теме, без
     * Темы-приёмника нельзя: с появлением потомка узел становится Разделом,
     * а на Разделе Задач и отметок Владения не бывает никогда (инвариант 1).
     *
     * Молча выполненная операция дала бы Задачи на Разделе: в дереве они
     * больше не находятся, в статистике не участвуют, и никакой ошибки
     * при этом не выдано (antipatterns.md, «Задачи или отметки на Разделе»).
     *
     * Отказ называет выход: тот же вызов с указанной Темой-приёмником
     * (ADR-0007), на которую содержимое переедет той же операцией.
     */
    private void refuseIfDeepeningLosesContent(TaxonomyNode node) {
        for (NodeContent kind : content) {
            Optional<String> held = kind.requiringTopic(node.id());
            if (held.isPresent()) {
                throw new TopicCarriesContentException("Узлу «" + node.name() + "» нельзя добавить потомка"
                        + " без Темы-приёмника: " + held.get() + ", а с потомком он станет Разделом."
                        + " Нужно указать Тему-приёмник — узел, на который они переедут");
            }
        }
    }

    /**
     * Приёмник допустим, если <b>после</b> операции у него не будет потомков
     * (design.md, «Проверка „приёмник — Тема“ считает состояние после
     * операции»). Вид узла не хранится, поэтому смотреть на нынешний
     * {@code isTopic()} мало: углубляемая Тема сейчас Тема, но потомок у неё
     * вот-вот появится; создаваемый потомок сейчас не существует, но будет
     * листом.
     *
     * Проверка живёт здесь, а не в области содержимого: только дерево знает,
     * каким оно станет.
     */
    private void refuseUnlessReceiverStaysATopic(TaxonomyNode deepened, TopicReceiver receiver) {
        if (!(receiver instanceof TopicReceiver.Existing existing)) {
            return;
        }
        TaxonomyNode node = existing(existing.id());
        if (node.id().equals(deepened.id())) {
            throw new ReceiverIsNotATopicException("Узел «" + node.name() + "» не может быть Темой-приёмником"
                    + " для самого себя: с появлением потомка он станет Разделом, а Задачи несут только Темы");
        }
        int children = nodes.countChildren(node.id());
        if (children > 0) {
            throw new ReceiverIsNotATopicException("Узел «" + node.name() + "» не может быть Темой-приёмником:"
                    + " у него есть потомки (" + children + "), а Задачи несут только Темы");
        }
    }

    /**
     * Проверка запрета на переезд узла в собственное поддерево — тем же
     * рекурсивным обходом, что нужен и для чтения. Средствами схемы такое
     * ограничение в PostgreSQL не выражается.
     */
    private void refuseIfInsideOwnSubtree(TaxonomyNode node, TaxonomyNode newParent) {
        boolean inside = nodes.findSubtree(node.id()).stream()
                .anyMatch(descendant -> descendant.id().equals(newParent.id()));
        if (inside) {
            throw new MoveIntoOwnSubtreeException("Узел «" + node.name() + "» нельзя перенести под «"
                    + newParent.name() + "»: это он сам или его собственный потомок");
        }
    }

    /**
     * Имя не должно повторяться среди потомков одного родителя.
     *
     * @param keep узел, который сам себе братом не считается (переименование
     *             и перемещение внутри того же родителя), либо {@code null}
     */
    private void refuseIfNameTaken(TaxonomyNodeId parent, String name, TaxonomyNodeId keep) {
        List<TaxonomyNode> siblings = parent == null ? nodes.findRoots() : nodes.findChildren(parent);
        boolean taken = siblings.stream()
                .filter(sibling -> !sibling.id().equals(keep))
                .anyMatch(sibling -> sibling.name().equals(name));
        if (taken) {
            throw new NameAlreadyTakenException(name);
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Имя узла не может быть пустым");
        }
        return trimmed;
    }

    private TaxonomyNode existing(TaxonomyNodeId id) {
        return nodes.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "Узла рубрикатора с идентификатором " + id.value() + " не существует"));
    }

    /** Плоский список поддерева — в потомков по родителю, с сохранением порядка. */
    private static Map<Long, List<TaxonomyNode>> byParent(List<TaxonomyNode> flat) {
        Map<Long, List<TaxonomyNode>> children = new LinkedHashMap<>();
        for (TaxonomyNode node : flat) {
            node.parentNode().ifPresent(parent -> children
                    .computeIfAbsent(parent.value(), key -> new ArrayList<>())
                    .add(node));
        }
        return children;
    }

    private static TaxonomyBranch assemble(TaxonomyNode node, Map<Long, List<TaxonomyNode>> byParent) {
        List<TaxonomyBranch> children = byParent.getOrDefault(node.id().value(), List.of()).stream()
                .map(child -> assemble(child, byParent))
                .toList();
        return new TaxonomyBranch(node, children);
    }

    private static void collectPaths(TaxonomyBranch branch, String prefix, List<TaxonomyPath> paths) {
        String path = prefix.isEmpty() ? branch.node().name() : prefix + " / " + branch.node().name();
        paths.add(new TaxonomyPath(branch.node().id(), path));
        branch.children().forEach(child -> collectPaths(child, path, paths));
    }
}
