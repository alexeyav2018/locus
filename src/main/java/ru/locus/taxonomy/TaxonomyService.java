package ru.locus.taxonomy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public TaxonomyService(TaxonomyRepository nodes) {
        this.nodes = nodes;
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
     * Узел, названный полным путём от корня, — то, что показывает правая
     * панель экрана.
     *
     * Путь собирается подъёмом по родителям, а не построением всего дерева:
     * ради одного узла обходить дерево незачем, а глубина измеряется
     * единицами уровней. Подъём ограничен по числу шагов — по той же причине,
     * что и рекурсивный спуск: испорченные данные должны давать ошибку,
     * а не вечный цикл.
     */
    public TaxonomyPath path(TaxonomyNodeId id) {
        TaxonomyNode node = existing(id);
        Deque<String> names = new ArrayDeque<>();
        TaxonomyNode current = node;
        for (int step = 0; step < TaxonomyRepository.MAX_DEPTH; step++) {
            names.addFirst(current.name());
            TaxonomyNodeId parent = current.parent();
            if (parent == null) {
                return new TaxonomyPath(node.id(), String.join(" / ", names));
            }
            current = existing(parent);
        }
        throw new IllegalStateException("Подъём от узла " + id.value() + " к корню достиг предела глубины "
                + TaxonomyRepository.MAX_DEPTH + ": похоже на цикл в дереве");
    }

    /**
     * Заводит узел. {@code parent} {@code null} — узел становится корневым.
     *
     * Вид узла при этом не назначается и нигде не сохраняется: новый узел
     * потомков не имеет, значит он Тема; появится потомок — станет Разделом
     * сам собой.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public TaxonomyNodeId create(String name, TaxonomyNodeId parent) {
        String trimmed = requireName(name);
        if (parent != null) {
            existing(parent);
        }
        refuseIfNameTaken(parent, trimmed, null);
        return nodes.create(trimmed, parent);
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
     * Единственная проверка пустоты узла: сюда дописывается каждое новое
     * условие, и искать его потом надо в одном месте, а не по всем вызовам
     * удаления.
     *
     * <p>Сегодня условие одно — у узла нет потомков. Оно неполно, и это
     * известно заранее. Содержимым узла считается всё, что на нём висит,
     * и <b>появление каждой из трёх сущностей обязано пополнить эту
     * проверку</b>:
     *
     * <ul>
     *   <li>Задачи — на Теме; приходят с работой {@code problem-catalog};</li>
     *   <li>Теоретические материалы — на любом узле; работа
     *       {@code theory-materials};</li>
     *   <li>отметки Владения — на паре «Тема × Метод»; работа
     *       {@code mastery-marks}.</li>
     * </ul>
     *
     * <p>Забытое пополнение — тихая потеря данных: узел уходит вместе
     * с накопленными суждениями учителей об учениках, и восстановить их
     * неоткуда (ADR-0007). Удаление узла <i>с</i> содержимым — не эта
     * операция, а отдельная, с распределением задач и предупреждением
     * о числе исчезающих отметок; она приедет с {@code rubricator-restructure}.
     */
    private void refuseUnlessEmpty(TaxonomyNode node) {
        int children = nodes.countChildren(node.id());
        if (children > 0) {
            throw new NodeNotEmptyException("У узла «" + node.name() + "» есть потомки (" + children
                    + "): узлы снимаются по одному, снизу вверх");
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
