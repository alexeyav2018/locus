package ru.locus.dictionary;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;

/**
 * Экран словарей: слева Методы, рядом Характеристики, справа действия над
 * выбранной записью.
 *
 * Экран один на оба словаря: они ведутся в одном подходе и одним человеком,
 * а при разметке задачи нужны оба сразу. Запись выбирается параметром
 * запроса, то есть обычной ссылкой из списка: правая панель — функция адреса,
 * а не состояния в браузере, поэтому скрипт для неё не нужен, а сам выбор
 * адресуем (ADR-0020).
 *
 * Выбор один на страницу: ссылка в списке Методов несёт только
 * {@code ?method=}, ссылка в списке Характеристик — только
 * {@code ?characteristic=}. Две одновременно выбранные записи означали бы
 * две панели действий, а действие всегда относится к одной записи.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяют сервисы на каждой операции, поэтому отказ
 * происходит одинаково и при нажатии кнопки, и при обращении по прямому
 * адресу.
 *
 * Роль здесь читается ровно для одного — показывать ли формы правки.
 * Правами это не является: разметка ничего не разрешает и не запрещает.
 */
@Controller
public class DictionaryController {

    /**
     * «Запись не выбрана» для шаблона. Идентификаторы положительны
     * по построению ({@link SolutionMethodId}, {@link CharacteristicId}),
     * поэтому нуль не может совпасть ни с одной записью. Значение вместо
     * {@code null} — чтобы сравнение в шаблоне было обычным сравнением чисел,
     * без проверки на отсутствие.
     */
    private static final long NOTHING_SELECTED = 0;

    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;
    private final CurrentUser currentUser;

    public DictionaryController(SolutionMethodService methods,
                                CharacteristicService characteristics,
                                CurrentUser currentUser) {
        this.methods = methods;
        this.characteristics = characteristics;
        this.currentUser = currentUser;
    }

    @GetMapping(Addresses.DICTIONARIES)
    public String dictionaries(@RequestParam(required = false) Long method,
                               @RequestParam(required = false) Long characteristic,
                               Model model) {
        return render(method, characteristic, model);
    }

    @PostMapping(Addresses.DICTIONARIES + "/methods")
    public String createMethod(@RequestParam String name, Model model) {
        try {
            return atMethod(methods.create(name).value());
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            return refused(refusal, null, null, model);
        }
    }

    @PostMapping(Addresses.DICTIONARIES + "/methods/{id}/name")
    public String renameMethod(@PathVariable long id, @RequestParam String name, Model model) {
        try {
            methods.rename(new SolutionMethodId(id), name);
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            return refused(refusal, id, null, model);
        }
        return atMethod(id);
    }

    /**
     * Снимает Метод и возвращает к словарям без выбора: самой записи больше
     * нет, а места, к которому стоило бы вернуться, у плоского списка нет —
     * в отличие от дерева, где остаётся родитель.
     */
    @PostMapping(Addresses.DICTIONARIES + "/methods/{id}/deletion")
    public String deleteMethod(@PathVariable long id, Model model) {
        try {
            methods.delete(new SolutionMethodId(id));
        } catch (IllegalArgumentException refusal) {
            return refused(refusal, id, null, model);
        }
        return "redirect:" + Addresses.DICTIONARIES;
    }

    @PostMapping(Addresses.DICTIONARIES + "/characteristics")
    public String createCharacteristic(@RequestParam String name, Model model) {
        try {
            return atCharacteristic(characteristics.create(name).value());
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            return refused(refusal, null, null, model);
        }
    }

    @PostMapping(Addresses.DICTIONARIES + "/characteristics/{id}/name")
    public String renameCharacteristic(@PathVariable long id, @RequestParam String name, Model model) {
        try {
            characteristics.rename(new CharacteristicId(id), name);
        } catch (NameAlreadyTakenException | IllegalArgumentException refusal) {
            return refused(refusal, null, id, model);
        }
        return atCharacteristic(id);
    }

    @PostMapping(Addresses.DICTIONARIES + "/characteristics/{id}/deletion")
    public String deleteCharacteristic(@PathVariable long id, Model model) {
        try {
            characteristics.delete(new CharacteristicId(id));
        } catch (IllegalArgumentException refusal) {
            return refused(refusal, null, id, model);
        }
        return "redirect:" + Addresses.DICTIONARIES;
    }

    /**
     * Собирает страницу: оба словаря целиком, справа — выбранная запись.
     *
     * Запись, которой нет, отказом не считается: её могли удалить в соседней
     * вкладке или прислать ссылкой на исчезнувшее. Словари при этом показать
     * можно и нужно — а действий над несуществующей записью не предлагается.
     */
    private String render(Long method, Long characteristic, Model model) {
        model.addAttribute("methods", methods.all());
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("administrator", currentUser.account().hasRole(Role.ADMINISTRATOR));

        long selectedMethod = NOTHING_SELECTED;
        if (method != null) {
            try {
                SolutionMethod chosen = methods.method(new SolutionMethodId(method));
                model.addAttribute("selectedMethod", chosen);
                selectedMethod = chosen.id().value();
            } catch (IllegalArgumentException gone) {
                model.addAttribute("error", gone.getMessage());
            }
        }
        model.addAttribute("selectedMethodId", selectedMethod);

        long selectedCharacteristic = NOTHING_SELECTED;
        if (characteristic != null) {
            try {
                Characteristic chosen = characteristics.characteristic(new CharacteristicId(characteristic));
                model.addAttribute("selectedCharacteristic", chosen);
                selectedCharacteristic = chosen.id().value();
            } catch (IllegalArgumentException gone) {
                model.addAttribute("error", gone.getMessage());
            }
        }
        model.addAttribute("selectedCharacteristicId", selectedCharacteristic);

        return "dictionary/dictionaries";
    }

    /**
     * Возврат к затронутой записи: параметр держит выбор, якорь — место
     * в списке. Без них на каждой правке теряется и то, и другое.
     */
    private static String atMethod(long id) {
        return "redirect:" + Addresses.DICTIONARIES + "?method=" + id + "#method-" + id;
    }

    private static String atCharacteristic(long id) {
        return "redirect:" + Addresses.DICTIONARIES + "?characteristic=" + id + "#characteristic-" + id;
    }

    private String refused(RuntimeException refusal, Long method, Long characteristic, Model model) {
        String message = refusal.getMessage();
        String page = render(method, characteristic, model);
        model.addAttribute("error", message);
        return page;
    }
}
