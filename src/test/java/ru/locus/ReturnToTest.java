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
    void carriedAddsTheInnerReturnBeforeTheAnchor() {
        assertThat(ReturnTo.carried("/dictionaries", "/problems/intake/3?from=%2Fassistant"))
                .isEqualTo("/dictionaries?from=%2Fproblems%2Fintake%2F3%3Ffrom%3D%252Fassistant");
        assertThat(ReturnTo.carried("/taxonomy?node=5#node-5", "/assistant?conversation=2"))
                .isEqualTo("/taxonomy?node=5&from=%2Fassistant%3Fconversation%3D2#node-5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "//evil.example", "", "javascript:alert(1)"})
    void carriedLeavesTheAddressAloneWithoutAnInnerReturn(String from) {
        assertThat(ReturnTo.carried("/taxonomy?node=5#node-5", from)).isEqualTo("/taxonomy?node=5#node-5");
        assertThat(ReturnTo.carried("/dictionaries", null)).isEqualTo("/dictionaries");
    }

    @Test
    void locationEncodesWhatCannotGoIntoTheHeaderAndKeepsTheEncoded() {
        assertThat(ReturnTo.location("/assistant?conversation=2")).isEqualTo("/assistant?conversation=2");
        assertThat(ReturnTo.location("/problems?q=корень x"))
                .isEqualTo("/problems?q=%D0%BA%D0%BE%D1%80%D0%B5%D0%BD%D1%8C%20x");
        assertThat(ReturnTo.location("/a?b={c}\"")).isEqualTo("/a?b=%7Bc%7D%22");
        assertThat(ReturnTo.location("/problems/intake?from=%2Fassistant%3Fconversation%3D2"))
                .isEqualTo("/problems/intake?from=%2Fassistant%3Fconversation%3D2");
    }

    @Test
    void tooLongIsDropped() {
        assertThat(ReturnTo.safe("/" + "a".repeat(ReturnTo.MAX_LENGTH))).isEmpty();
        assertThat(ReturnTo.safe("/" + "a".repeat(ReturnTo.MAX_LENGTH - 1))).isPresent();
    }
}
