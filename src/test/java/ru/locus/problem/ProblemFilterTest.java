package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Задача 1.2: условия поиска приводятся к одному виду до того, как ими
 * начнут пользоваться (ADR-0031).
 *
 * Проверяется не «поля записались», а то, ради чего запись заведена:
 * у «ничего не выбрано» ровно одно представление и у «способ не указан»
 * ровно одно значение. Два представления одного состояния — это тихое
 * расхождение: код, собирающий запрос, обязан был бы помнить про оба,
 * а забыть про один из них не мешает ничто.
 */
class ProblemFilterTest {

    private static final SolutionMethodId METHOD = new SolutionMethodId(1);
    private static final CharacteristicId CHARACTERISTIC = new CharacteristicId(2);

    @Test
    void aFilterWithoutConditionsKnowsItIsEmpty() {
        assertThat(ProblemFilter.empty().isEmpty())
                .as("Фильтр без условий отбирает всю библиотеку")
                .isTrue();
    }

    @Test
    void anyConditionMakesTheFilterNonEmpty() {
        assertThat(withNode(new TaxonomyNodeId(1)).isEmpty()).isFalse();
        assertThat(withMethods(List.of(METHOD), null).isEmpty()).isFalse();
        assertThat(withCharacteristics(List.of(CHARACTERISTIC)).isEmpty()).isFalse();
        assertThat(withPart(ExamPart.SECOND).isEmpty()).isFalse();
    }

    /** «Ничего не выбрано» имеет одно представление — пустой список. */
    @Test
    void anAbsentListBecomesAnEmptyOne() {
        ProblemFilter filter = new ProblemFilter(null, null, null, null, null, null);

        assertThat(filter.methods()).isEmpty();
        assertThat(filter.characteristics()).isEmpty();
        assertThat(filter.isEmpty()).isTrue();
    }

    /** Умолчание расширяющее: сужающее прочтение спрятало бы часть библиотеки. */
    @Test
    void anUnsetModeBecomesAny() {
        ProblemFilter filter = new ProblemFilter(null, List.of(METHOD), null, List.of(CHARACTERISTIC), null, null);

        assertThat(filter.methodMode()).isEqualTo(MatchMode.ANY);
        assertThat(filter.characteristicMode()).isEqualTo(MatchMode.ANY);
    }

    @Test
    void aChosenModeIsKept() {
        ProblemFilter filter = withMethods(List.of(METHOD), MatchMode.ALL);

        assertThat(filter.methodMode()).isEqualTo(MatchMode.ALL);
        assertThat(filter.characteristicMode())
                .as("способ у Характеристик свой и от Методов не зависит")
                .isEqualTo(MatchMode.ANY);
    }

    @Test
    void aValueChosenTwiceIsOneValue() {
        ProblemFilter filter = withMethods(List.of(METHOD, METHOD), MatchMode.ALL);

        assertThat(filter.methods()).containsExactly(METHOD);
    }

    private static ProblemFilter withNode(TaxonomyNodeId node) {
        return new ProblemFilter(node, List.of(), null, List.of(), null, null);
    }

    private static ProblemFilter withMethods(List<SolutionMethodId> methods, MatchMode mode) {
        return new ProblemFilter(null, methods, mode, List.of(), null, null);
    }

    private static ProblemFilter withCharacteristics(List<CharacteristicId> characteristics) {
        return new ProblemFilter(null, List.of(), null, characteristics, null, null);
    }

    private static ProblemFilter withPart(ExamPart part) {
        return new ProblemFilter(null, List.of(), null, List.of(), null, part);
    }
}
