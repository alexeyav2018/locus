package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Отбор Задач по разметке — требования «Поиск отбирает Задачи по четырём
 * условиям» и «Способ соединения значений внутри условия выбирает учитель».
 *
 * Проверка идёт на настоящей базе: почти всё проверяемое здесь живёт в SQL,
 * собранном по частям, а не в Java. Собранный по частям запрос — то место,
 * где ошибка не падает, а возвращает правдоподобный список: лишнее условие
 * сужает выдачу, потерянное — расширяет, и по одному ответу не видно ни то,
 * ни другое.
 *
 * Обход поддерева здесь не проверяется: репозиторий получает Темы готовым
 * списком, а разворачивает поддерево рубрикатор. Обход проверяется там, где
 * он живёт, — в {@code TaxonomyRepositoryTest} и в {@code ProblemSearchTest}.
 *
 * Тесты идут на одной базе, поэтому обстановку каждый заводит себе сам,
 * с неповторяющимися именами, и сравнивает результат со своими Задачами,
 * а не с содержимым библиотеки целиком ({@link TestLibrary}).
 */
class ProblemSearchRepositoryTest extends IntegrationTest {

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private TestLibrary library;

    @Test
    void searchByTopicReturnsProblemsOfThatTopicOnly() {
        TaxonomyNodeId mine = library.topic();
        TaxonomyNodeId other = library.topic();
        ProblemId wanted = library.problem(List.of(mine), List.of(library.method()), List.of(), ExamPart.SECOND);
        ProblemId unwanted = library.problem(List.of(other), List.of(library.method()), List.of(), ExamPart.SECOND);

        List<Problem> found = problems.search(List.of(mine), List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, null);

        assertThat(ids(found)).contains(wanted).doesNotContain(unwanted);
    }

    @Test
    void searchByMethodReturnsProblemsMarkedWithIt() {
        SolutionMethodId wantedMethod = library.method();
        TaxonomyNodeId topic = library.topic();
        ProblemId wanted = library.problem(List.of(topic), List.of(wantedMethod), List.of(), ExamPart.SECOND);
        ProblemId unwanted = library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.SECOND);

        List<Problem> found =
                problems.search(null, List.of(wantedMethod), MatchMode.ANY, List.of(), MatchMode.ANY, null);

        assertThat(ids(found)).contains(wanted).doesNotContain(unwanted);
    }

    @Test
    void searchByCharacteristicReturnsProblemsMarkedWithIt() {
        CharacteristicId wantedCharacteristic = library.characteristic();
        TaxonomyNodeId topic = library.topic();
        ProblemId wanted = library.problem(List.of(topic), List.of(library.method()),
                List.of(wantedCharacteristic), ExamPart.SECOND);
        ProblemId unwanted = library.problem(List.of(topic), List.of(library.method()),
                List.of(library.characteristic()), ExamPart.SECOND);

        List<Problem> found = problems.search(null, List.of(), MatchMode.ANY,
                List.of(wantedCharacteristic), MatchMode.ANY, null);

        assertThat(ids(found)).contains(wanted).doesNotContain(unwanted);
    }

    @Test
    void searchByPartReturnsProblemsOfThatPart() {
        TaxonomyNodeId topic = library.topic();
        ProblemId first = library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.FIRST);
        ProblemId second = library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.SECOND);

        List<Problem> found =
                problems.search(List.of(topic), List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, ExamPart.FIRST);

        assertThat(ids(found)).containsExactly(first).doesNotContain(second);
    }

    /** Сценарий «Условия соединяются по „и“». */
    @Test
    void differentConditionsAreCombinedWithAnd() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        ProblemId matchesBoth = library.problem(List.of(topic), List.of(method), List.of(), ExamPart.SECOND);
        ProblemId matchesMethodOnly = library.problem(List.of(topic), List.of(method), List.of(), ExamPart.FIRST);

        List<Problem> found = problems.search(List.of(topic), List.of(method), MatchMode.ANY,
                List.of(), MatchMode.ANY, ExamPart.SECOND);

        assertThat(ids(found))
                .as("совпадения по одному условию из двух недостаточно")
                .containsExactly(matchesBoth)
                .doesNotContain(matchesMethodOnly);
    }

    /** Сценарий «Условий не задано». */
    @Test
    void withoutAnyConditionTheWholeLibraryComesBack() {
        ProblemId one = library.problem(List.of(library.topic()), List.of(library.method()),
                List.of(), ExamPart.FIRST);
        ProblemId two = library.problem(List.of(library.topic()), List.of(library.method()),
                List.of(), ExamPart.SECOND);

        List<Problem> found = problems.search(null, List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, null);

        assertThat(ids(found)).contains(one, two);
    }

    /** Сценарий «Задача, размеченная двумя Темами, возвращается один раз». */
    @Test
    void aProblemMarkedWithTwoOfTheGivenTopicsComesBackOnce() {
        TaxonomyNodeId one = library.topic();
        TaxonomyNodeId two = library.topic();
        ProblemId problem = library.problem(List.of(one, two), List.of(library.method()), List.of(), ExamPart.SECOND);

        List<Problem> found =
                problems.search(List.of(one, two), List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, null);

        assertThat(ids(found))
                .as("подзапрос exists не порождает дублей, в отличие от соединения")
                .containsExactly(problem);
    }

    /**
     * Пустой список Тем — испорченный вызов, а не «ничего не нашлось».
     *
     * Поддерево всегда содержит сам узел, поэтому пустым список Тем быть
     * не может. Верни запрос на него пустой результат — он выглядел бы
     * честным ответом, и найти причину было бы не по чему.
     */
    @Test
    void anEmptyTopicListIsRefusedInsteadOfReturningNothing() {
        assertThatThrownBy(() ->
                problems.search(List.of(), List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("поддерево");
    }

    /** Сценарий «Порядок результата». */
    @Test
    void theResultIsOrderedByNumber() {
        TaxonomyNodeId topic = library.topic();
        ProblemId earlier = library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.SECOND);
        ProblemId later = library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.SECOND);

        List<Problem> found =
                problems.search(List.of(topic), List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, null);

        assertThat(ids(found)).containsExactly(earlier, later);
    }

    /** Сценарий «Все сразу». */
    @Test
    void theAllModeKeepsOnlyProblemsMarkedWithEveryChosenValue() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId one = library.method();
        SolutionMethodId two = library.method();
        ProblemId both = library.problem(List.of(topic), List.of(one, two), List.of(), ExamPart.SECOND);
        ProblemId onlyOne = library.problem(List.of(topic), List.of(one), List.of(), ExamPart.SECOND);

        List<Problem> found = problems.search(List.of(topic), List.of(one, two), MatchMode.ALL,
                List.of(), MatchMode.ANY, null);

        assertThat(ids(found)).containsExactly(both).doesNotContain(onlyOne);
    }

    /** Сценарий «Любое из». */
    @Test
    void theAnyModeKeepsProblemsMarkedWithAtLeastOneChosenValue() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId one = library.method();
        SolutionMethodId two = library.method();
        ProblemId both = library.problem(List.of(topic), List.of(one, two), List.of(), ExamPart.SECOND);
        ProblemId onlyOne = library.problem(List.of(topic), List.of(one), List.of(), ExamPart.SECOND);

        List<Problem> found = problems.search(List.of(topic), List.of(one, two), MatchMode.ANY,
                List.of(), MatchMode.ANY, null);

        assertThat(ids(found))
                .as("Задача с обоими Методами приходит один раз, а не дважды")
                .containsExactly(both, onlyOne);
    }

    /** Сценарий «Способы у Методов и Характеристик независимы». */
    @Test
    void theModeOfMethodsAndTheModeOfCharacteristicsAreIndependent() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId firstMethod = library.method();
        SolutionMethodId secondMethod = library.method();
        CharacteristicId firstCharacteristic = library.characteristic();
        CharacteristicId secondCharacteristic = library.characteristic();

        ProblemId wanted = library.problem(List.of(topic), List.of(firstMethod, secondMethod),
                List.of(firstCharacteristic), ExamPart.SECOND);
        ProblemId oneMethodOnly = library.problem(List.of(topic), List.of(firstMethod),
                List.of(firstCharacteristic, secondCharacteristic), ExamPart.SECOND);

        List<Problem> found = problems.search(List.of(topic),
                List.of(firstMethod, secondMethod), MatchMode.ALL,
                List.of(firstCharacteristic, secondCharacteristic), MatchMode.ANY,
                null);

        assertThat(ids(found))
                .as("все выбранные Методы, но любая из выбранных Характеристик")
                .containsExactly(wanted)
                .doesNotContain(oneMethodOnly);
    }

    /** Сценарий «Одно выбранное значение». */
    @Test
    void withOneChosenValueBothModesGiveTheSameResult() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        ProblemId problem = library.problem(List.of(topic), List.of(method), List.of(), ExamPart.SECOND);

        List<Problem> all = problems.search(List.of(topic), List.of(method), MatchMode.ALL,
                List.of(), MatchMode.ANY, null);
        List<Problem> any = problems.search(List.of(topic), List.of(method), MatchMode.ANY,
                List.of(), MatchMode.ANY, null);

        assertThat(ids(all)).containsExactly(problem);
        assertThat(ids(any)).isEqualTo(ids(all));
    }

    /**
     * Сценарий «Ни одного выбранного значения».
     *
     * Самый неочевидный край всей работы. Понятое буквально, «все сразу»
     * из нуля значений истинно для всякой Задачи, а «любое из» из нуля —
     * ложно для всякой: механически собранный запрос вернул бы здесь пусто
     * на непустой библиотеке. Ни одно из двух прочтений не имеется в виду,
     * когда список просто не тронут, поэтому пустое условие снимается,
     * и оба способа дают то же, что и отсутствие условия вовсе.
     */
    @Test
    void anEmptyListIsTheAbsenceOfAConditionInBothModes() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(List.of(topic), List.of(library.method()), List.of(), ExamPart.SECOND);

        List<Problem> withAll = problems.search(List.of(topic), List.of(), MatchMode.ALL,
                List.of(), MatchMode.ALL, null);
        List<Problem> withAny = problems.search(List.of(topic), List.of(), MatchMode.ANY,
                List.of(), MatchMode.ANY, null);

        assertThat(ids(withAny))
                .as("пустой список при «любое из» не должен обнулять выдачу")
                .containsExactly(problem);
        assertThat(ids(withAll)).isEqualTo(ids(withAny));
    }

    /**
     * Разметка дочитывается пачкой на весь список — и каждой Задаче достаётся
     * своя.
     *
     * Раскладка по Задачам идёт в Java, а не запросом на каждую, и ошибка
     * в ней не падает: Задача получила бы чужие метки и выглядела бы
     * размеченной.
     */
    @Test
    void everyFoundProblemGetsItsOwnMarkup() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId firstMethod = library.method();
        SolutionMethodId secondMethod = library.method();
        CharacteristicId characteristic = library.characteristic();

        ProblemId bare = library.problem(List.of(topic), List.of(firstMethod), List.of(), ExamPart.FIRST);
        ProblemId marked = library.problem(List.of(topic), List.of(firstMethod, secondMethod),
                List.of(characteristic), ExamPart.SECOND);

        List<Problem> found =
                problems.search(List.of(topic), List.of(), MatchMode.ANY, List.of(), MatchMode.ANY, null);

        assertThat(found).hasSize(2);
        Problem readBare = found.get(0);
        Problem readMarked = found.get(1);
        assertThat(readBare.id()).isEqualTo(bare);
        assertThat(readBare.methods()).containsExactly(firstMethod);
        assertThat(readBare.characteristics()).isEmpty();
        assertThat(readBare.part()).isEqualTo(ExamPart.FIRST);
        assertThat(readMarked.id()).isEqualTo(marked);
        assertThat(readMarked.methods()).containsExactlyInAnyOrder(firstMethod, secondMethod);
        assertThat(readMarked.characteristics()).containsExactly(characteristic);
        assertThat(readMarked.topics()).containsExactly(topic);
    }

    private static List<ProblemId> ids(List<Problem> found) {
        return found.stream().map(Problem::id).toList();
    }
}
