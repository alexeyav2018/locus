package ru.locus.student;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;

/**
 * Экраны Групп: список своих Групп с формой заведения и страница Группы
 * с переименованием, составом и удалением.
 *
 * Отдельный экран, а не панель на экране Учеников, как у словарей:
 * у Группы есть состав, и на одной странице с полным списком Учеников
 * он не помещается (design.md, «Экраны»).
 *
 * Состав задаётся целиком одной формой: галочки по всем Ученикам владельца,
 * снятая галочка исключает. Поштучные «добавить»/«исключить» дали бы два
 * адреса и два обработчика ради того же результата.
 *
 * Контроллер тонкий и владельца не знает — ровно как {@link StudentController}:
 * владелец подставляется сервисом (ADR-0027), чужая Группа и чужой Ученик
 * в составе отвечают 404 через {@link GroupNotFoundException}
 * и {@link StudentNotFoundException}, которые здесь не перехватываются.
 */
@Controller
public class GroupController {

    private final GroupService groups;
    private final StudentService students;

    public GroupController(GroupService groups, StudentService students) {
        this.groups = groups;
        this.students = students;
    }

    @GetMapping(Addresses.GROUPS)
    public String list(Model model) {
        return renderList(model);
    }

    @PostMapping(Addresses.GROUPS)
    public String create(@RequestParam(required = false) String name, Model model) {
        try {
            return atGroup(groups.create(name).value());
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderList(model);
        }
    }

    @GetMapping(Addresses.GROUPS + "/{id}")
    public String group(@PathVariable long id, Model model) {
        return renderGroup(new GroupId(id), model);
    }

    @PostMapping(Addresses.GROUPS + "/{id}/name")
    public String rename(@PathVariable long id, @RequestParam(required = false) String name, Model model) {
        GroupId groupId = new GroupId(id);
        try {
            groups.rename(groupId, name);
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
            return renderGroup(groupId, model);
        }
        return atGroup(id);
    }

    /**
     * Состав целиком. Параметр не обязателен: форма без единой галочки
     * не шлёт его вовсе, а означает пустую Группу.
     */
    @PostMapping(Addresses.GROUPS + "/{id}/members")
    public String setMembers(@PathVariable long id,
                             @RequestParam(required = false) List<Long> studentIds) {
        List<StudentId> members = studentIds == null
                ? List.of()
                : studentIds.stream().map(StudentId::new).toList();
        groups.setMembers(new GroupId(id), members);
        return atGroup(id);
    }

    /** Удаляет Группу и возвращает к списку: страницы больше нет. */
    @PostMapping(Addresses.GROUPS + "/{id}/deletion")
    public String delete(@PathVariable long id) {
        groups.delete(new GroupId(id));
        return "redirect:" + Addresses.GROUPS;
    }

    private String renderList(Model model) {
        model.addAttribute("groups", groups.all());
        return "group/list";
    }

    /**
     * Страница Группы: сама запись, все Ученики владельца для галочек
     * и множество идентификаторов состава — чтобы шаблон ставил галочку
     * простой проверкой принадлежности, а не искал по списку.
     */
    private String renderGroup(GroupId id, Model model) {
        Group group = groups.group(id);
        Set<Long> memberIds = groups.members(group.id()).stream()
                .map(member -> member.id().value())
                .collect(Collectors.toSet());
        model.addAttribute("group", group);
        model.addAttribute("students", students.all());
        model.addAttribute("memberIds", memberIds);
        return "group/group";
    }

    private static String atGroup(long id) {
        return "redirect:" + Addresses.GROUPS + "/" + id;
    }
}
