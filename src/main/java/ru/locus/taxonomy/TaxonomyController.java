package ru.locus.taxonomy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemDistribution;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.theory.TheoryService;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;

/**
 * Экран рубрикатора: слева дерево, справа действия над выбранным узлом.
 *
 * Узел выбирается параметром запроса, то есть обычной ссылкой из дерева:
 * правая панель — функция адреса, а не состояния в браузере, поэтому
 * скрипт для неё не нужен, а сам выбор адресуем — ссылку можно сохранить.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяет {@link TaxonomyService} на каждой операции,
 * поэтому отказ происходит одинаково и при нажатии кнопки, и при обращении
 * по прямому адресу.
 *
 * Роль здесь читается ровно для одного — показывать ли формы правки.
 * Правами это не является: разметка ничего не разрешает и не запрещает.
 */
@Controller
public class TaxonomyController {

    /**
     * «Узел не выбран» для шаблона. Идентификаторы положительны по построению
     * ({@link TaxonomyNodeId}), поэтому нуль не может совпасть ни с одним
     * узлом. Значение вместо {@code null} — чтобы сравнение в шаблоне было
     * обычным сравнением чисел, без проверки на отсутствие.
     */
    private static final long NOTHING_SELECTED = 0;

    /**
     * Значение поля «Тема-приёмник», означающее создаваемого потомка.
     * Слово, а не число: у потомка идентификатора ещё нет, а «особое» число
     * однажды ушло бы в запрос как настоящее ({@link TopicReceiver}).
     */
    static final String CREATED_CHILD = "created";

    /**
     * Поля формы распределения: {@code destination[<Задача>]} — Тема-приёмник
     * этой Задачи. Имя поля несёт ключ карты, значение — её значение; так
     * форма отдаёт всю карту «Задача → приёмник» разом, и полноту карты
     * проверяет сервис Задач, а не порядок полей в разметке.
     */
    private static final String DESTINATION_PREFIX = "destination[";
    private static final String DESTINATION_SUFFIX = "]";

    private final TaxonomyService taxonomy;

    /**
     * Задачи выбранной Темы. Зависимость от области Задач — только здесь,
     * в контроллере: сервис дерева о Задачах не знает и знать не должен,
     * иначе области связались бы в кольцо (design.md, «Проверки в чужих
     * областях»).
     */
    private final ProblemService problems;

    /**
     * Теоретические материалы выбранного узла — свои и унаследованные
     * от предков. Зависимость от области теории живёт здесь по той же причине
     * и на тех же правах, что и зависимость от области Задач: односторонняя
     * связь контроллера, а не сервиса.
     */
    private final TheoryService theory;

    private final CurrentUser currentUser;

    public TaxonomyController(TaxonomyService taxonomy,
                              ProblemService problems,
                              TheoryService theory,
                              CurrentUser currentUser) {
        this.taxonomy = taxonomy;
        this.problems = problems;
        this.theory = theory;
        this.currentUser = currentUser;
    }

    @GetMapping(Addresses.TAXONOMY)
    public String tree(@RequestParam(required = false) Long node, Model model) {
        return render(node, model);
    }

    /**
     * Заводит узел; у Темы с Задачами — углубление с Темой-приёмником
     * (ADR-0007). Приёмник приходит тем же запросом, что и имя потомка:
     * это одна операция, и разделять её на «создать» и «перевесить» нельзя
     * (см. {@link TaxonomyService#create(String, TaxonomyNodeId, TopicReceiver)}).
     *
     * <p>Пустое поле приёмника — «не указан»: у Темы без Задач форма прежняя
     * и приёмника не отдаёт, а Тема с Задачами без него получит отказ —
     * от сервиса, не отсюда.
     */
    @PostMapping(Addresses.TAXONOMY)
    public String create(@RequestParam String name,
                         @RequestParam(required = false) Long parentId,
                         @RequestParam(required = false) String receiver,
                         Model model) {
        try {
            return atNode(taxonomy.create(name, nodeId(parentId), receiver(receiver)).value());
        } catch (NameAlreadyTakenException | TopicCarriesContentException | ReceiverIsNotATopicException
                 | IllegalArgumentException refusal) {
            return refused(refusal, parentId, model);
        }
    }

    @PostMapping(Addresses.TAXONOMY + "/{id}/name")
    public String rename(@PathVariable long id, @RequestParam String name, Model model) {
        try {
            taxonomy.rename(new TaxonomyNodeId(id), name);
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            return refused(refusal, id, model);
        }
        return atNode(id);
    }

    @PostMapping(Addresses.TAXONOMY + "/{id}/parent")
    public String move(@PathVariable long id,
                       @RequestParam(required = false) Long parentId,
                       Model model) {
        try {
            taxonomy.move(new TaxonomyNodeId(id), nodeId(parentId));
        } catch (MoveIntoOwnSubtreeException | NameAlreadyTakenException | IllegalArgumentException refusal) {
            return refused(refusal, id, model);
        }
        return atNode(id);
    }

    /**
     * Снимает узел и возвращает на его прежнего родителя: самого узла больше
     * нет, а место, где работал Администратор, теряться не должно.
     */
    @PostMapping(Addresses.TAXONOMY + "/{id}/deletion")
    public String delete(@PathVariable long id, Model model) {
        try {
            TaxonomyNodeId parent = taxonomy.node(new TaxonomyNodeId(id)).parent();
            taxonomy.delete(new TaxonomyNodeId(id));
            return parent == null ? "redirect:" + Addresses.TAXONOMY : atNode(parent.value());
        } catch (NodeNotEmptyException | IllegalArgumentException refusal) {
            return refused(refusal, id, model);
        }
    }

    /**
     * Снимает Тему с Задачами, распределив их по Темам-приёмникам поштучно
     * (ADR-0007), и возвращает на прежнего родителя — как {@link #delete}.
     *
     * <p>Форма отдаёт карту «Задача → приёмник» полями
     * {@code destination[<Задача>]}; здесь она лишь собирается в
     * {@link ProblemDistribution} и передаётся дереву. Полноту карты
     * проверяет область Задач, приёмников — дерево; контроллер ни того,
     * ни другого не знает. Собирать карту здесь можно: контроллер — то
     * единственное место у дерева, которому область Задач известна
     * (см. поле {@link #problems}).
     */
    @PostMapping(Addresses.TAXONOMY + "/{id}/distribution")
    public String distribute(@PathVariable long id,
                             @RequestParam Map<String, String> form,
                             Model model) {
        try {
            TaxonomyNodeId parent = taxonomy.node(new TaxonomyNodeId(id)).parent();
            taxonomy.deleteWithDistribution(new TaxonomyNodeId(id), distribution(form));
            return parent == null ? "redirect:" + Addresses.TAXONOMY : atNode(parent.value());
        } catch (NodeNotEmptyException | ReceiverIsNotATopicException | IllegalArgumentException refusal) {
            return refused(refusal, id, model);
        }
    }

    /**
     * Собирает страницу: дерево слева, выбранный узел справа.
     *
     * Узел, которого нет, отказом не считается: его могли удалить в соседней
     * вкладке или прислать ссылкой на исчезнувшее. Дерево при этом показать
     * можно и нужно — а действий над несуществующим узлом не предлагается.
     *
     * Задачи выбранной Темы и Теоретические материалы выбранного узла читаются
     * здесь же: библиотека живёт на этом экране (design.md, «Экран»).
     * Зависимость от обеих областей есть только у контроллера — {@link
     * TaxonomyService} о них по-прежнему не знает, и это проверяется отдельно.
     *
     * Материалы спрашиваются на любом узле, а не только на Теме: теория лежит
     * и на Разделе (ADR-0032), и наследуется вниз — пустой список у Раздела
     * означал бы, что положенное на него никому не показывают.
     *
     * Перестройка предлагается Администратору на Теме с Задачами: тогда
     * форма потомка требует Тему-приёмник, а вместо обычного удаления —
     * снятие с распределением, для которого нужны Темы дерева и число
     * исчезающих отметок Владения. Счёт отметок спрашивается только у
     * Администратора и только здесь: это единственное место, где ему
     * видно личное чужих учителей (ADR-0005), и Учителю сервис его
     * не отдаст вовсе.
     */
    private String render(Long node, Model model) {
        boolean administrator = currentUser.account().hasRole(Role.ADMINISTRATOR);
        model.addAttribute("tree", taxonomy.tree());
        model.addAttribute("paths", taxonomy.paths());
        model.addAttribute("administrator", administrator);

        long selected = NOTHING_SELECTED;
        model.addAttribute("problems", List.of());
        model.addAttribute("materials", List.of());
        model.addAttribute("restructure", false);
        if (node != null) {
            try {
                TaxonomyNode chosen = taxonomy.node(new TaxonomyNodeId(node));
                model.addAttribute("selected", chosen);
                model.addAttribute("selectedPath", taxonomy.path(chosen.id()).path());
                selected = chosen.id().value();
                if (chosen.isTopic()) {
                    List<Problem> onTopic = problems.problemsOf(chosen.id());
                    model.addAttribute("problems", onTopic);
                    if (administrator && !onTopic.isEmpty()) {
                        model.addAttribute("restructure", true);
                        model.addAttribute("receivers", taxonomy.topicPaths());
                        model.addAttribute("distributionReceivers",
                                taxonomy.receiverPathsAfterRemoving(chosen.id()));
                        model.addAttribute("createdChild", CREATED_CHILD);
                        model.addAttribute("vanishingMarks", taxonomy.countVanishingMarks(chosen.id()));
                    }
                }
                model.addAttribute("materials", theory.materialsOn(chosen.id()));
            } catch (IllegalArgumentException gone) {
                model.addAttribute("error", gone.getMessage());
            }
        }
        model.addAttribute("selectedId", selected);
        return "taxonomy/tree";
    }

    /**
     * Возврат к затронутому узлу: параметр держит выбор, якорь — место
     * в дереве. Без них на каждой правке теряется и то, и другое.
     */
    private static String atNode(long id) {
        return "redirect:" + Addresses.TAXONOMY + "?node=" + id + "#node-" + id;
    }

    private String refused(RuntimeException refusal, Long node, Model model) {
        String message = refusal.getMessage();
        String page = render(node, model);
        model.addAttribute("error", message);
        return page;
    }

    private static TaxonomyNodeId nodeId(Long value) {
        return value == null ? null : new TaxonomyNodeId(value);
    }

    /**
     * Поле приёмника из формы углубления: пусто — не указан, {@link
     * #CREATED_CHILD} — создаваемый потомок, иначе — идентификатор Темы.
     * Слово, которого форма не отдаёт, — неверный ввод, а не «не указан»:
     * молчаливое «не указан» превратило бы опечатку в отказ по другой
     * причине.
     */
    private static TopicReceiver receiver(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (CREATED_CHILD.equals(value)) {
            return TopicReceiver.CREATED_CHILD;
        }
        try {
            return TopicReceiver.existing(new TaxonomyNodeId(Long.parseLong(value)));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Тема-приёмник указана неверно: " + value);
        }
    }

    /**
     * Карта «Задача → приёмник» из полей формы распределения. Прочие поля
     * пропускаются. Задача без выбранного приёмника — отказ здесь же,
     * до сервиса: это неполный ввод, а не неполное распределение, и текст
     * отказа должен назвать поле, а не «не названную Задачу».
     */
    private static ProblemDistribution distribution(Map<String, String> form) {
        Map<ProblemId, TaxonomyNodeId> destinations = new LinkedHashMap<>();
        form.forEach((field, value) -> {
            if (!field.startsWith(DESTINATION_PREFIX) || !field.endsWith(DESTINATION_SUFFIX)) {
                return;
            }
            String key = field.substring(DESTINATION_PREFIX.length(), field.length() - DESTINATION_SUFFIX.length());
            ProblemId problem;
            try {
                problem = new ProblemId(Long.parseLong(key));
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Поле распределения указано неверно: " + field);
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("У Задачи № " + problem.value() + " не указана Тема-приёмник");
            }
            try {
                destinations.put(problem, new TaxonomyNodeId(Long.parseLong(value)));
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Тема-приёмник Задачи № " + problem.value()
                        + " указана неверно: " + value);
            }
        });
        return new ProblemDistribution(destinations);
    }
}
