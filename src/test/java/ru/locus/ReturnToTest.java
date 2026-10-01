package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Требование «Контекстный возврат»: параметр {@code from} принимается только
 * как адрес внутри системы, прочее молча отбрасывается.
 */
class ReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/students", "/students?withdrawn=true",
            "/problems?method=1&method=2&part=FIRST", "/taxonomy?node=3#node-3"})
    void innerAddressesPass(String address) {
        assertThat(ReturnTo.safe(address)).contains(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://evil.example/x", "//evil.example", "/\\evil.example",
            "javascript:alert(1)", "students", "evil.example/students", "/a\nb", "/a\rb", "/a\tb",
            "/redirect?u=https://evil.example"})
    void foreignOrBrokenAddressesAreDropped(String address) {
        assertThat(ReturnTo.safe(address)).isEmpty();
    }

    @Test
    void emptyAndMissingAreDropped() {
        assertThat(ReturnTo.safe(null)).isEmpty();
        assertThat(ReturnTo.safe("")).isEmpty();
    }

    @Test
    void tooLongIsDropped() {
        assertThat(ReturnTo.safe("/" + "a".repeat(ReturnTo.MAX_LENGTH))).isEmpty();
        assertThat(ReturnTo.safe("/" + "a".repeat(ReturnTo.MAX_LENGTH - 1))).isPresent();
    }
}
