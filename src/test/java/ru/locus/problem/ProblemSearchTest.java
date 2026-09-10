package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;

/**
 * Поиск на уровне сервиса — требование «Отбор по узлу идёт с обходом
 * поддерева» и та часть требования о результате, которая говорит про имена.
 *
 * Отбор как таковой проверен ниже, в {@code ProblemSearchRepositoryTest};
 * здесь проверяется ровно то, что добавляет сервис: разворот узла
 * в поддерево и перевод разметки в имена. Обход проверяется на дереве
 * настоящей глубины, а не на одном уровне: ошибка «обошли только детей»
 * на глубине один неотличима от правильного ответа.
 *
 * Прав поиск не требует — ни входа, ни роли: чтение общей библиотеки
 * закрыто грубым рубежом {@code SecurityConfig}, и проверяется он через
 * настоящий вход в {@code SearchIsNotFilteredByOwnerTest}, а не здесь.
 *
 * Тесты идут на одной базе, поэтому каждый заводит обстановку себе сам
 * и сравнивает результат со своими Задачами ({@link TestLibrary}).
 */
class ProblemSearchTest extends IntegrationTest {

    @Autowired
    private ProblemService problems;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private SolutionMethodService methods;

    @Autowired
    private CharacteristicService characteristics;

    /** Сценарий «Отбор по Разделу находит Задачи дальних потомков». */
    @Test
    void searchBySectionFindsProblemMarkedThreeLevelsBelowIt() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId middle = library.topic(section);
        TaxonomyNodeId lower = library.topic(middle);
        TaxonomyNodeId deep = library.topic(lower);
        ProblemId deepProblem = problem(deep);

        List<FoundProblem> found = problems.search(nodeFilter(section));

        assertThat(ids(found)).contains(deepProblem);
    }

    /** Сценарий «Отбор по Теме»: соседние Темы не захватываются. */
    @Test
    void searchByTopicDoesNotCatchSiblingTopics() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId mine = library.topic(section);
        TaxonomyNodeId sibling = library.topic(section);
        ProblemId wanted = problem(mine);
        ProblemId neighbour = problem(sibling);

        List<FoundProblem> found = problems.search(nodeFilter(mine));

        assertThat(ids(found)).containsExactly(wanted).doesNotContain(neighbour);
    }

    /** Сценарий «Соседняя ветвь не попадает». */
    @Test
    void problemFromAnotherBranchIsNotFound() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId mine = library.topic(section);
        TaxonomyNodeId otherBranch = library.topic();
        ProblemId wanted = problem(mine);
        ProblemId elsewhere = problem(library.topic(otherBranch));

        List<FoundProblem> found = problems.search(nodeFilter(section));

        assertThat(ids(found)).contains(wanted).doesNotContain(elsewhere);
    }

    /** Сценарий «Условий не задано». */
    @Test
    void emptyFilterReturnsTheWholeLibrary() {
        ProblemId first = problem(library.topic());
        ProblemId second = problem(library.topic());

        List<FoundProblem> found = problems.search(ProblemFilter.empty());

        assertThat(ids(found)).contains(first, second);
    }

    /**
     * Сценарий «Узла не существует».
     *
     * Отказ приходит из рубрикатора — своего обхода у поиска нет, и своей
     * проверки существования тоже. Пустой результат вместо отказа был бы
     * неотличим от честного «ничего не нашлось».
     */
    @Test
    void searchByMissingNodeIsRefused() {
        assertThatThrownBy(() -> problems.search(nodeFilter(new TaxonomyNodeId(Long.MAX_VALUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не существует");
    }

    /** Сценарий «Все сразу»: два Метода сужают результат до Задачи с обоими. */
    @Test
    void allModeNarrowsTheResultToProblemsCarryingEveryChosenMethod() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId first = library.method();
        SolutionMethodId second = library.method();
        ProblemId both = library.problem(List.of(topic), List.of(first, second), List.of(), ExamPart.SECOND);
        ProblemId onlyFirst = library.problem(List.of(topic), List.of(first), List.of(), ExamPart.SECOND);

        List<FoundProblem> found = problems.search(new ProblemFilter(topic,
                List.of(first, second), MatchMode.ALL, List.of(), null, null));

        assertThat(ids(found)).containsExactly(both).doesNotContain(onlyFirst);
    }

    /** Сценарий «Любое из»: те же два Метода расширяют результат. */
    @Test
    void anyModeWidensTheResultToProblemsCarryingEitherChosenMethod() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId first = library.method();
        SolutionMethodId second = library.method();
        ProblemId both = library.problem(List.of(topic), List.of(first, second), List.of(), ExamPart.SECOND);
        ProblemId onlyFirst = library.problem(List.of(topic), List.of(first), List.of(), ExamPart.SECOND);

        List<FoundProblem> found = problems.search(new ProblemFilter(topic,
                List.of(first, second), MatchMode.ANY, List.of(), null, null));

        assertThat(ids(found)).containsExactly(both, onlyFirst);
    }

    /**
     * Сценарий «Строка результата» в части имён: разметка названа именами,
     * а Темы — полными путями от корня.
     *
     * Путь проверяется целиком, а не по вхождению имени Темы: короткое имя
     * тоже содержало бы его, а различать одинаково названные Темы под разными
     * родителями надо именно путём.
     */
    @Test
    void resultCarriesFullTopicPathsAndDictionaryNames() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();
        ProblemId id = library.problem(List.of(topic), List.of(method), List.of(characteristic), ExamPart.SECOND);

        FoundProblem found = onlyOne(problems.search(nodeFilter(topic)));

        assertThat(found.problem().id()).isEqualTo(id);
        assertThat(found.topicPaths()).containsExactly(
                taxonomy.node(section).name() + " / " + taxonomy.node(topic).name());
        assertThat(found.methodNames()).containsExactly(methods.method(method).name());
        assertThat(found.characteristicNames()).containsExactly(characteristics.characteristic(characteristic).name());
    }

    private ProblemId problem(TaxonomyNodeId topic) {
        return library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.SECOND);
    }

    private static ProblemFilter nodeFilter(TaxonomyNodeId node) {
        return new ProblemFilter(node, List.of(), null, List.of(), null, null);
    }

    private static List<ProblemId> ids(List<FoundProblem> found) {
        return found.stream().map(one -> one.problem().id()).toList();
    }

    private static FoundProblem onlyOne(List<FoundProblem> found) {
        assertThat(found).hasSize(1);
        return found.get(0);
    }
}
