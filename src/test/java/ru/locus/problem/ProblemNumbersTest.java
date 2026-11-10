package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Задача 1.1: поле номеров разбирается одним правилом для страницы поиска
 * и для Помощника (ADR-0064).
 */
class ProblemNumbersTest {

    @Test
    void separatorsMixFreely() {
        assertThat(ProblemNumbers.parse("12, 3;7\n5 ,; 9").values())
                .containsExactly(12L, 3L, 7L, 5L, 9L);
    }

    @Test
    void repeatedNumberIsOneNumber() {
        assertThat(ProblemNumbers.parse("3 3 3").values()).containsExactly(3L);
    }

    @Test
    void leadingAndTrailingSeparatorsAreIgnored() {
        assertThat(ProblemNumbers.parse(" ,3; ").values()).containsExactly(3L);
    }

    @Test
    void foreignPieceIsNamed() {
        assertThatThrownBy(() -> ProblemNumbers.parse("3, пять"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Не разобрано: «пять»");
    }

    @Test
    void everyForeignPieceIsNamedAtOnce() {
        assertThatThrownBy(() -> ProblemNumbers.parse("3-7, 4, №5"))
                .hasMessage("Не разобрано: «3-7», «№5»");
    }

    @Test
    void numberBeyondLongIsForeign() {
        assertThatThrownBy(() -> ProblemNumbers.parse("99999999999999999999"))
                .hasMessage("Не разобрано: «99999999999999999999»");
    }

    @Test
    void emptyFieldIsRefused() {
        assertThatThrownBy(() -> ProblemNumbers.parse(""))
                .hasMessage("Не задано ни одного номера");
        assertThatThrownBy(() -> ProblemNumbers.parse(" ,;\n "))
                .hasMessage("Не задано ни одного номера");
        assertThatThrownBy(() -> ProblemNumbers.parse(null))
                .hasMessage("Не задано ни одного номера");
    }

    @Test
    void emptyListFromAssistantIsRefused() {
        assertThatThrownBy(() -> ProblemNumbers.of(List.of()))
                .hasMessage("Не задано ни одного номера");
    }

    @Test
    void zeroIsParsedButIsNoProblemId() {
        ProblemNumbers numbers = ProblemNumbers.parse("0, 4");
        assertThat(numbers.values()).containsExactly(0L, 4L);
        assertThat(numbers.ids()).containsExactly(new ProblemId(4));
    }
}
