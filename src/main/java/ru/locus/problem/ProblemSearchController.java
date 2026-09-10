package ru.locus.problem;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;

/**
 * Экран поиска Задач по разметке: форма условий сверху, найденное под ней.
 *
 * Условия передаются параметрами запроса, форма идёт методом {@code GET},
 * и результат поиска получает собственный адрес: его можно сохранить,
 * переслать и вернуться к нему кнопкой «назад» — для подбора Задач
 * к контрольной это ровно то, что нужно. Отправка {@code POST} дала бы
 * страницу без адреса (design.md, «Экран — GET /problems, условия в адресе»).
 *
 * Множественный выбор приходит повторяющимся параметром
 * ({@code ?method=3&method=7}), который Spring сам собирает в список.
 * Скрипта для этого не нужно, и ограничение ADR-0020 не задевается.
 *
 * Контроллер отдельный, а не шестая операция {@link ProblemController}:
 * тот и без того ведёт заведение, правку, просмотр, замену обоих файлов
 * и снятие. Общий путь {@code /problems} у {@code GET} здесь и {@code POST}
 * там законен и означает ровно то, что означает: один ресурс, разные
 * действия.
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Отбор, обход поддерева и перевод разметки в имена делает
 * {@link ProblemService#search}; здесь только собирается {@link ProblemFilter}
 * из параметров и наполняются списки выбора.
 *
 * Прав поиску не добавляется: вход уже потребован грубым рубежом
 * {@code SecurityConfig}, а роль Администратора запретила бы поиск Учителю,
 * то есть тому, ради кого он делается. Фильтра по владельцу здесь нет
 * и быть не может — Задачи общие (ADR-0027).
 */
@Controller
public class ProblemSearchController {

    private final ProblemService problems;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;

    public ProblemSearchController(ProblemService problems,
                                   TaxonomyService taxonomy,
                                   SolutionMethodService methods,
                                   CharacteristicService characteristics) {
        this.problems = problems;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
    }

    /**
     * Поиск. Все условия необязательны: экран, открытый без единого
     * параметра, показывает всю библиотеку — это тот же поиск без условий,
     * а не особый случай «ещё не искали».
     *
     * Узла, которого нет, здесь не пропускают молча: отказ приходит
     * из рубрикатора и показывается вместо результата. Пустой список
     * на его месте был бы неотличим от честного «ничего не нашлось»
     * (дельта спеки, сценарий «Узла не существует»).
     */
    @GetMapping(Addresses.PROBLEMS)
    public String search(@RequestParam(required = false) Long node,
                         @RequestParam(name = "method", required = false) List<Long> methodIds,
                         @RequestParam(required = false) MatchMode methodMode,
                         @RequestParam(name = "characteristic", required = false) List<Long> characteristicIds,
                         @RequestParam(required = false) MatchMode characteristicMode,
                         @RequestParam(required = false) ExamPart part,
                         Model model) {
        ProblemFilter filter = new ProblemFilter(
                node == null ? null : new TaxonomyNodeId(node),
                methodIds(methodIds), methodMode,
                characteristicIds(characteristicIds), characteristicMode,
                part);
        fillChoices(model);
        model.addAttribute("filter", filter);
        try {
            List<FoundProblem> found = problems.search(filter);
            model.addAttribute("found", found);
            model.addAttribute("foundCount", found.size());
        } catch (IllegalArgumentException refusal) {
            model.addAttribute("error", refusal.getMessage());
        }
        return "problem/search";
    }

    /**
     * Из чего выбирают условия.
     *
     * Узлы — <b>все</b> ({@code paths()}), а не только Темы: и Раздел
     * законное условие поиска, потому что здесь он не носитель Задач,
     * а способ назвать их множество. Тем он отличается от формы разметки,
     * где выбирают из {@code topicPaths()}: разметить Задачу Разделом
     * нельзя (инвариант 10).
     *
     * Методы Темы отдельным списком, как в форме разметки, здесь не идут:
     * Тема ещё не выбрана — её как раз и ищут.
     */
    private void fillChoices(Model model) {
        model.addAttribute("paths", taxonomy.paths());
        model.addAttribute("methods", methods.all());
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("parts", ExamPart.values());
        model.addAttribute("modes", MatchMode.values());
    }

    private static List<SolutionMethodId> methodIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(SolutionMethodId::new).toList();
    }

    private static List<CharacteristicId> characteristicIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(CharacteristicId::new).toList();
    }
}
