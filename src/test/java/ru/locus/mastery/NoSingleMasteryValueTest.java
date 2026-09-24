package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Признак готовности карточки `mastery-views`: нигде нет единого значения
 * владения (ADR-0013). Проверка отражением по {@link Distribution} —
 * там, где такое значение могло бы незаметно появиться в будущей правке.
 */
class NoSingleMasteryValueTest {

    private static final Set<Class<?>> FORBIDDEN_RETURN_TYPES =
            Set.of(MasteryStatus.class, double.class, Double.class, float.class, Float.class, BigDecimal.class);

    @Test
    void distributionHasNoMethodReturningASingleMasteryValue() {
        for (Method method : Distribution.class.getDeclaredMethods()) {
            assertThat(FORBIDDEN_RETURN_TYPES)
                    .as("метод %s не должен возвращать единое значение владения", method.getName())
                    .doesNotContain(method.getReturnType());
        }
    }
}
