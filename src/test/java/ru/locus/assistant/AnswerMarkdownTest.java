package ru.locus.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ответ Помощника на странице Разговора (design.md assistant-chat, Р7):
 * только теги самого CommonMark и только внутренние ссылки.
 */
class AnswerMarkdownTest {

    private static final String BACK = "/assistant?conversation=7";
    private static final String FROM = "from=%2Fassistant%3Fconversation%3D7";

    @Test
    void paragraphsListsAndCodeAreKept() {
        String html = AnswerMarkdown.html("""
                Первый абзац.

                - один
                - два

                Вызов `find_problems` и блок:

                ```
                код
                ```
                """, BACK);

        assertThat(html).contains("<p>Первый абзац.</p>")
                .contains("<ul>\n<li>один</li>\n<li>два</li>\n</ul>")
                .contains("<code>find_problems</code>")
                .contains("<pre><code>код\n</code></pre>");
    }

    @Test
    void singleLineBreakBecomesBreak() {
        assertThat(AnswerMarkdown.html("Шаг один\nШаг два", BACK)).contains("Шаг один<br />\nШаг два");
    }

    @Test
    void rawHtmlIsText() {
        String html = AnswerMarkdown.html("<script>alert(1)</script>\n\nи <b>в строке</b>", BACK);

        assertThat(html).doesNotContain("<script").doesNotContain("<b>")
                .contains("&lt;script&gt;").contains("&lt;b&gt;");
    }

    @Test
    void internalLinkGetsReturnToTheConversation() {
        assertThat(AnswerMarkdown.html("[Задачи](/problems)", BACK))
                .contains("<a href=\"/problems?" + FROM + "\">Задачи</a>");
    }

    @Test
    void existingReturnIsReplacedAndAnchorKept() {
        assertThat(AnswerMarkdown.html("[Тема](/taxonomy?node=3&from=/students#node-3)", BACK))
                .contains("<a href=\"/taxonomy?node=3&amp;" + FROM + "#node-3\">Тема</a>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "//example.com", "javascript:alert(1)"})
    void foreignLinkIsTextWithItsAddress(String address) {
        String html = AnswerMarkdown.html("[сайт](" + address + ")", BACK);

        assertThat(html).doesNotContain("<a").contains("сайт (" + address + ")");
    }

    @Test
    void autolinkIsTextOnce() {
        String html = AnswerMarkdown.html("<https://example.com>", BACK);

        assertThat(html).doesNotContain("<a").contains("<p>https://example.com</p>");
    }

    @Test
    void internalAddressInCodeIsLink() {
        assertThat(AnswerMarkdown.html("Вычитка: `/problems/intake/1`", BACK))
                .contains("<a href=\"/problems/intake/1?" + FROM + "\">/problems/intake/1</a>")
                .doesNotContain("<code>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"find_problems", "https://example.com", "/", "/2", "/problems и т.д."})
    void otherCodeStaysCode(String code) {
        String html = AnswerMarkdown.html("`" + code + "`", BACK);

        assertThat(html).doesNotContain("<a").contains("<code>" + code + "</code>");
    }

    @Test
    void codeInsideLinkStaysCode() {
        assertThat(AnswerMarkdown.html("[`/problems/intake/1`](/problems/intake/1)", BACK))
                .contains("<a href=\"/problems/intake/1?" + FROM + "\"><code>/problems/intake/1</code></a>");
    }

    @Test
    void imageIsItsCaption() {
        String html = AnswerMarkdown.html("![снимок листа](https://example.com/a.png)", BACK);

        assertThat(html).doesNotContain("<img").doesNotContain("example.com").contains("снимок листа");
    }
}
