package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Данные шапки для возврата и раскрытия блоков (ADR-0042): разбор адреса
 * отклонённого действия и выбор адреса «назад».
 */
class ShellAdviceTest {

    private static ShellAdvice.Shell shell(String back, String here, String posted) {
        return new ShellAdvice.Shell("teacher", false, true, "students", back, here, posted, java.util.Map.of());
    }

    @Test
    void backGoesToTheGivenAddressOrToTheDefault() {
        assertThat(shell("/problems?part=FIRST", null, null).backOr("/problems"))
                .isEqualTo("/problems?part=FIRST");
        assertThat(shell(null, null, null).backOr("/problems")).isEqualTo("/problems");
    }

    @Test
    void blockOpensOnlyForTheRefusedAction() {
        ShellAdvice.Shell refused = shell(null, null, "/taxonomy/7/name");

        assertThat(refused.opened("/name")).isTrue();
        assertThat(refused.opened("/parent", "/name")).isTrue();
        assertThat(refused.opened("/parent")).isFalse();
        assertThat(refused.opened("/deletion", "/distribution")).isFalse();
    }

    @Test
    void buildKeepsWhatIsKnownAndDropsWhatIsBlank() {
        assertThat(new ShellAdvice.Build("1.0.0", "a1b2c3d")).isEqualTo(new ShellAdvice.Build("1.0.0", "a1b2c3d"));
        assertThat(new ShellAdvice.Build("1.0.0", null).commit()).isNull();
        assertThat(new ShellAdvice.Build("1.0.0", "  ").commit()).isNull();
        assertThat(new ShellAdvice.Build(null, null).version()).isNull();
    }

    @Test
    void nothingOpensWithoutARefusal() {
        assertThat(shell(null, "/taxonomy?node=7", null).opened("/name", "/parent")).isFalse();
    }
}
