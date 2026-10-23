package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Разбор аргументов вызова инструмента (design.md, Р6).
 *
 * Число из JSON приходит {@code Integer}, {@code Long} или {@code Double}
 * в зависимости от величины и записи — все три читаются как целое;
 * отсутствующий обязательный аргумент и аргумент не того типа —
 * отказ, называющий аргумент по имени, чтобы модель поправила вызов.
 */
class ArgumentsTest {

    @Test
    void integerLongAndWholeDoubleAreAllRead() {
        Arguments arguments = Arguments.of(Map.of("a", 5, "b", 7L, "c", 9.0, "d", "11"));

        assertThat(arguments.requireLong("a")).isEqualTo(5);
        assertThat(arguments.requireLong("b")).isEqualTo(7);
        assertThat(arguments.requireLong("c")).isEqualTo(9);
        assertThat(arguments.requireLong("d")).isEqualTo(11);
        assertThat(arguments.requireInt("a")).isEqualTo(5);
    }

    @Test
    void fractionIsNotAWholeNumber() {
        assertThatThrownBy(() -> Arguments.of(Map.of("page", 1.5)).requireLong("page"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page")
                .hasMessageContaining("целым");
    }

    @Test
    void missingRequiredArgumentIsNamed() {
        assertThatThrownBy(() -> Arguments.none().requireLong("draft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("«draft»")
                .hasMessageContaining("обязателен");
        assertThatThrownBy(() -> Arguments.none().requireString("slot"))
                .hasMessageContaining("«slot»");
        assertThatThrownBy(() -> Arguments.none().requireObject("frame"))
                .hasMessageContaining("«frame»");
    }

    @Test
    void optionalArgumentsAreEmptyWhenAbsent() {
        Arguments none = Arguments.none();

        assertThat(none.optionalLong("x")).isEmpty();
        assertThat(none.optionalInt("x")).isEmpty();
        assertThat(none.optionalString("x")).isEmpty();
        assertThat(none.optionalObject("x")).isEmpty();
        assertThat(none.longs("x")).isEmpty();
        assertThat(none.objects("x")).isEmpty();
        assertThat(none.has("x")).isFalse();
    }

    @Test
    void blankStringCountsAsAbsent() {
        assertThat(Arguments.of(Map.of("part", "  ")).optionalString("part")).isEmpty();
        assertThat(Arguments.of(Map.of("part", "FIRST")).requireString("part")).isEqualTo("FIRST");
    }

    @Test
    void wrongTypeIsRefusedByName() {
        assertThatThrownBy(() -> Arguments.of(Map.of("page", "two")).requireLong("page"))
                .hasMessageContaining("«page»");
        assertThatThrownBy(() -> Arguments.of(Map.of("slot", 3)).requireString("slot"))
                .hasMessageContaining("«slot»")
                .hasMessageContaining("строкой");
        assertThatThrownBy(() -> Arguments.of(Map.of("methods", 3)).longs("methods"))
                .hasMessageContaining("«methods»")
                .hasMessageContaining("списком");
    }

    @Test
    void listsOfNumbersAndObjectsAreRead() {
        Arguments arguments = Arguments.of(Map.of(
                "methods", List.of(1, 2L, 3.0),
                "lines", List.of(Map.of("draft", 4, "from", 1, "to", 2))));

        assertThat(arguments.longs("methods")).containsExactly(1L, 2L, 3L);
        List<Arguments> lines = arguments.objects("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().requireLong("draft")).isEqualTo(4);
        assertThat(lines.getFirst().requireInt("to")).isEqualTo(2);
    }

    @Test
    void nestedObjectAndRealNumbersAreRead() {
        Arguments arguments = Arguments.of(Map.of("frame", Map.of("left", 0.25, "top", 0, "width", "0.5")));

        Arguments frame = arguments.requireObject("frame");
        assertThat(frame.requireDouble("left")).isEqualTo(0.25);
        assertThat(frame.requireDouble("top")).isEqualTo(0.0);
        assertThat(frame.requireDouble("width")).isEqualTo(0.5);
        assertThatThrownBy(() -> frame.requireDouble("height")).hasMessageContaining("«height»");
    }

    @Test
    void enumIsReadByNameRegardlessOfCase() {
        Arguments arguments = Arguments.of(Map.of("slot", "condition", "part", "SECOND", "mode", "every"));

        assertThat(arguments.requireEnum("slot", Slot.class)).isEqualTo(Slot.CONDITION);
        assertThat(arguments.optionalEnum("part", Part.class)).contains(Part.SECOND);
        assertThat(arguments.optionalEnum("absent", Part.class)).isEmpty();
        assertThatThrownBy(() -> arguments.requireEnum("mode", Slot.class))
                .hasMessageContaining("«mode»")
                .hasMessageContaining("CONDITION, SOLUTION")
                .hasMessageContaining("«every»");
        assertThatThrownBy(() -> arguments.requireEnum("absent", Slot.class)).hasMessageContaining("обязателен");
    }

    private enum Slot { CONDITION, SOLUTION }

    private enum Part { FIRST, SECOND }
}
