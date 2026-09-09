package ru.locus.taxonomy;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.problem.ProblemService;
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

    private final TaxonomyService taxonomy;

    /**
     * Задачи выбранной Темы. Зависимость от области Задач — только здесь,
     * в контроллере: сервис дерева о Задачах не знает и знать не должен,
     * иначе области связались бы в кольцо (design.md, «Проверки в чужих
     * областях»).
     */
    private final ProblemService problems;

    private final CurrentUser currentUser;

    public TaxonomyController(TaxonomyService taxonomy, ProblemService problems, CurrentUser currentUser) {
        this.taxonomy = taxonomy;
        this.problems = problems;
        this.currentUser = currentUser;
    }

    @GetMapping(Addresses.TAXONOMY)
    public String tree(@RequestParam(required = false) Long node, Model model) {
        return render(node, model);
    }

    @PostMapping(Addresses.TAXONOMY)
    public String create(@RequestParam String name,
                         @RequestParam(required = false) Long parentId,
                         Model model) {
        try {
            return atNode(taxonomy.create(name, nodeId(parentId)).value());
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
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
     * Собирает страницу: дерево слева, выбранный узел справа.
     *
     * Узел, которого нет, отказом не считается: его могли удалить в соседней
     * вкладке или прислать ссылкой на исчезнувшее. Дерево при этом показать
     * можно и нужно — а действий над несуществующим узлом не предлагается.
     *
     * Задачи выбранной Темы читаются здесь же: библиотека живёт на этом экране
     * (design.md, «Экран»). Зависимость от области Задач есть только у
     * контроллера — {@link TaxonomyService} о них по-прежнему не знает, и это
     * проверяется отдельно.
     */
    private String render(Long node, Model model) {
        model.addAttribute("tree", taxonomy.tree());
        model.addAttribute("paths", taxonomy.paths());
        model.addAttribute("administrator", currentUser.account().hasRole(Role.ADMINISTRATOR));

        long selected = NOTHING_SELECTED;
        model.addAttribute("problems", List.of());
        if (node != null) {
            try {
                TaxonomyNode chosen = taxonomy.node(new TaxonomyNodeId(node));
                model.addAttribute("selected", chosen);
                model.addAttribute("selectedPath", taxonomy.path(chosen.id()).path());
                selected = chosen.id().value();
                if (chosen.isTopic()) {
                    model.addAttribute("problems", problems.problemsOf(chosen.id()));
                }
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
}
