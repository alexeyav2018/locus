package ru.locus.theory;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import ru.locus.Addresses;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;

/**
 * Страницы Теоретического материала: заведение, правка и просмотр.
 *
 * Список материалов узла живёт не здесь, а в правой части экрана дерева
 * ({@code taxonomy/tree.html}) — по той же причине, по которой там живёт список
 * Задач: «выбрать узел» — единственная навигация, которая у библиотеки сегодня
 * есть, и второе дерево на отдельном экране разъехалось бы с первым сначала
 * оформлением, потом поведением (design.md, «Экран»).
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию и хранилищу (standards.md,
 * «Слои и границы»). Права проверяет {@link TheoryService} на каждой операции,
 * поэтому отказ происходит одинаково и при нажатии кнопки, и при обращении
 * по прямому адресу.
 *
 * Роль здесь читается ровно для одного — показывать ли формы правки.
 * Правами это не является: разметка ничего не разрешает и не запрещает.
 */
@Controller
public class TheoryController {

    private final TheoryService theory;
    private final TaxonomyService taxonomy;
    private final CurrentUser currentUser;

    public TheoryController(TheoryService theory, TaxonomyService taxonomy, CurrentUser currentUser) {
        this.theory = theory;
        this.taxonomy = taxonomy;
        this.currentUser = currentUser;
    }

    /**
     * Форма заведения. Узел, с которого пришли, выбран заранее: материал
     * заводят с экрана дерева, стоя на узле, и заставлять выбирать его второй
     * раз — приглашение ошибиться.
     */
    @GetMapping(Addresses.THEORY + "/new")
    public String form(@RequestParam(required = false) Long node, Model model) {
        model.addAttribute("chosenNode", node == null ? 0L : node);
        model.addAttribute("nodePaths", taxonomy.paths());
        return "theory/form";
    }

    @PostMapping(Addresses.THEORY)
    public String create(@RequestParam(required = false) String title,
                         @RequestParam(required = false) Long node,
                         @RequestParam(required = false) String link,
                         @RequestParam(required = false) MultipartFile file,
                         Model model) {
        try {
            TheoryMaterialId created = theory.create(title, nodeId(node), uploaded(file, link));
            return "redirect:" + Addresses.THEORY + "/" + created.value();
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return form(node, model);
        }
    }

    /** Просмотр материала: название, узел, вид содержимого и путь к нему. */
    @GetMapping(Addresses.THEORY + "/{id}")
    public String material(@PathVariable long id, Model model) {
        TheoryMaterial material = theory.material(new TheoryMaterialId(id));
        model.addAttribute("material", material);
        model.addAttribute("nodePath", taxonomy.path(material.node()).path());
        model.addAttribute("administrator", currentUser.account().hasRole(Role.ADMINISTRATOR));
        return "theory/material";
    }

    /** Форма правки — та же, что и заведения, но с заполненным материалом. */
    @GetMapping(Addresses.THEORY + "/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        TheoryMaterial material = theory.material(new TheoryMaterialId(id));
        model.addAttribute("material", material);
        model.addAttribute("chosenNode", material.node().value());
        model.addAttribute("nodePaths", taxonomy.paths());
        return "theory/form";
    }

    @PostMapping(Addresses.THEORY + "/{id}")
    public String edit(@PathVariable long id,
                       @RequestParam(required = false) String title,
                       @RequestParam(required = false) Long node,
                       Model model) {
        try {
            theory.edit(new TheoryMaterialId(id), title, nodeId(node));
        } catch (IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
        return "redirect:" + Addresses.THEORY + "/" + id;
    }

    /**
     * Замена содержимого: файл на другой файл, файл на ссылку, ссылка
     * на файл — одна и та же правка, и форма для неё одна.
     */
    @PostMapping(Addresses.THEORY + "/{id}/content")
    public String replaceContent(@PathVariable long id,
                                 @RequestParam(required = false) String link,
                                 @RequestParam(required = false) MultipartFile file,
                                 Model model) {
        try {
            theory.replaceContent(new TheoryMaterialId(id), uploaded(file, link));
        } catch (IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
        return "redirect:" + Addresses.THEORY + "/" + id;
    }

    /**
     * Снимает материал и возвращает на узел, где тот лежал: самого материала
     * больше нет, а место, где работал Администратор, теряться не должно.
     */
    @PostMapping(Addresses.THEORY + "/{id}/deletion")
    public String delete(@PathVariable long id, Model model) {
        try {
            TheoryMaterial material = theory.material(new TheoryMaterialId(id));
            long node = material.node().value();
            theory.delete(material.id());
            return "redirect:" + Addresses.TAXONOMY + "?node=" + node + "#node-" + node;
        } catch (IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
    }

    /**
     * Переход к приложенному файлу: подписанная ссылка выдаётся в момент
     * перехода и тут же уводит на неё.
     *
     * Ссылка не вставляется в разметку заранее, как это сделано у Задачи:
     * материалы показываются списком, и подписывать пришлось бы каждый файл
     * списка — включая те, которые никто не откроет. Срок жизни ссылки при
     * этом отсчитывался бы от показа списка, а не от нажатия, и на долго
     * открытой странице ссылки протухали бы молча.
     *
     * У материала-ссылки своего адреса здесь нет: внешний адрес стоит
     * в разметке как есть — подписывать чужой адрес нечем и незачем.
     */
    @GetMapping(Addresses.THEORY + "/{id}/file")
    public String file(@PathVariable long id) {
        return "redirect:" + theory.fileLink(new TheoryMaterialId(id));
    }

    private String refusedEdit(long id, RuntimeException refusal, Model model) {
        String message = refusal.getMessage();
        String page = edit(id, model);
        model.addAttribute("error", message);
        return page;
    }

    /**
     * Присланное содержимое к виду, понятному сервису. Пустое поле выбора
     * файла — это отсутствие файла, а не пустой файл: отказ «нужен файл либо
     * ссылка» должен звучать одинаково и когда поле не заполнено, и когда его
     * вовсе нет в запросе.
     *
     * Оба поля собираются в одну запись всегда, а не по очереди: какое из них
     * заполнено — как раз то, что выбирает человек, и разбирается это
     * в {@link TheoryService}, а не здесь.
     */
    private static UploadedContent uploaded(MultipartFile file, String link) {
        if (file == null || file.isEmpty()) {
            return new UploadedContent(null, null, link);
        }
        try {
            return new UploadedContent(file.getBytes(), file.getContentType(), link);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TaxonomyNodeId nodeId(Long value) {
        return value == null ? null : new TaxonomyNodeId(value);
    }
}
