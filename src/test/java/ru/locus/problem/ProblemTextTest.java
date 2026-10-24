package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.locus.file.FileKey;

/**
 * Состав Текста Задачи (design.md изменения {@code problem-text}, Р2, Р3):
 * оба текста или ни одного, пустой текст не текст, предел длины, Улика
 * только при тексте.
 *
 * Проверки стоят в компактном конструкторе, поэтому и проверяются на нём,
 * как у {@link ProblemTest}: текст в обход правил не должен существовать
 * ни в памяти, ни в базе.
 */
class ProblemTextTest {

    private static final FileKey EVIDENCE = new FileKey("evidence.pdf");

    @Test
    void bothTextsMakeAText() {
        ProblemText text = new ProblemText("Найдите $x$.", "$x = 2$.", EVIDENCE, null, 3);

        assertThat(text.of(ProblemSlot.CONDITION)).isEqualTo("Найдите $x$.");
        assertThat(text.of(ProblemSlot.SOLUTION)).isEqualTo("$x = 2$.");
        assertThat(text.evidenceOf(ProblemSlot.CONDITION)).isEqualTo(EVIDENCE);
        assertThat(text.evidenceOf(ProblemSlot.SOLUTION))
                .as("Улика необязательна: текст, набранный рукой, снимка не имеет")
                .isNull();
        assertThat(text.version()).isEqualTo(3);
    }

    /** Половинчатой Задачи нет (Р2). */
    @Test
    void oneTextIsRefused() {
        assertThatThrownBy(() -> new ProblemText("Найдите $x$.", null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("оба текста");
        assertThatThrownBy(() -> new ProblemText(null, "$x = 2$.", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("оба текста");
    }

    @Test
    void textBlankAfterTrimmingIsRefused() {
        assertThatThrownBy(() -> new ProblemText("Найдите $x$.", " \n\t ", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("оба текста");
    }

    @Test
    void textIsKeptAsGivenWithoutTrimming() {
        assertThat(new ProblemText("  Найдите $x$.\n", "$x = 2$.", null, null, 0).condition())
                .isEqualTo("  Найдите $x$.\n");
    }

    @Test
    void textLongerThanTheLimitIsRefusedNamingTheLimit() {
        String tooLong = "а".repeat(ProblemText.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new ProblemText("Найдите $x$.", tooLong, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ProblemText.MAX_LENGTH))
                .hasMessageContaining("решение");
    }

    /** Предел считается в знаках, а не в единицах UTF-16: символ вне BMP — один знак. */
    @Test
    void textOfExactlyTheLimitIsAccepted() {
        String atLimit = "𝑥".repeat(ProblemText.MAX_LENGTH);

        assertThat(new ProblemText(atLimit, "$x = 2$.", null, null, 0).condition()).isEqualTo(atLimit);
    }

    @Test
    void evidenceWithoutTextIsRefused() {
        assertThatThrownBy(() -> new ProblemText(null, null, EVIDENCE, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Улика");
        assertThatThrownBy(() -> new ProblemText(null, null, null, EVIDENCE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Улика");
    }

    @Test
    void negativeVersionIsRefused() {
        assertThatThrownBy(() -> new ProblemText("Найдите $x$.", "$x = 2$.", null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
