package ru.locus.assistant;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.commonmark.node.Code;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import ru.locus.ReturnTo;

/**
 * Ответ Помощника на странице Разговора (design.md assistant-chat, Р7):
 * Markdown в HTML с белым списком самого CommonMark.
 *
 * Сырой HTML выводится текстом. Ссылкой остаётся только адрес внутри системы
 * ({@link ReturnTo#safe}), и ведёт она с возвратом {@code from} на показанный
 * Разговор; внешний адрес и {@code javascript:} — текстом, адрес в скобках,
 * чтобы учитель видел, куда звали. Фрагмент кода, целиком состоящий
 * из внутреннего адреса, — такой же ссылкой (intake-answer-links). Картинка —
 * подписью: внешнюю браузер загрузил бы сам.
 */
public final class AnswerMarkdown {

    private static final Parser MARKDOWN = Parser.builder().build();

    private AnswerMarkdown() {
    }

    /**
     * @param markdown ответ Помощника
     * @param back     адрес показанного Разговора — {@code from} внутренних ссылок
     */
    public static String html(String markdown, String back) {
        HtmlRenderer renderer = HtmlRenderer.builder()
                .escapeHtml(true)
                .sanitizeUrls(true)
                .softbreak("<br />\n")
                .nodeRendererFactory(context -> new ImageAsCaption(context.getWriter()))
                .nodeRendererFactory(context -> new InternalLinksOnly(context, back))
                .nodeRendererFactory(context -> new InternalAddressCodeAsLink(context, back))
                .build();
        return renderer.render(MARKDOWN.parse(markdown));
    }

    /** Адрес с параметром {@code from}, заменённым на {@code back}; якорь сохраняется. */
    static String withReturn(String destination, String back) {
        String anchor = "";
        int hash = destination.indexOf('#');
        if (hash >= 0) {
            anchor = destination.substring(hash);
            destination = destination.substring(0, hash);
        }
        String path = destination;
        StringJoiner query = new StringJoiner("&");
        int question = destination.indexOf('?');
        if (question >= 0) {
            path = destination.substring(0, question);
            for (String parameter : destination.substring(question + 1).split("&")) {
                if (!parameter.isEmpty() && !parameter.equals("from") && !parameter.startsWith("from=")) {
                    query.add(parameter);
                }
            }
        }
        query.add("from=" + URLEncoder.encode(back, StandardCharsets.UTF_8));
        return path + "?" + query + anchor;
    }

    private static String plainText(Node node) {
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text literal) {
                text.append(literal.getLiteral());
            } else if (child instanceof Code code) {
                text.append(code.getLiteral());
            } else {
                text.append(plainText(child));
            }
        }
        return text.toString();
    }

    private record InternalLinksOnly(HtmlNodeRendererContext context, String back) implements NodeRenderer {

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Link.class);
        }

        @Override
        public void render(Node node) {
            Link link = (Link) node;
            HtmlWriter writer = context.getWriter();
            Optional<String> internal = ReturnTo.safe(link.getDestination());
            if (internal.isPresent()) {
                String href = context.encodeUrl(withReturn(internal.get(), back));
                writer.tag("a", Map.of("href", href));
                renderChildren(link);
                writer.tag("/a");
                return;
            }
            renderChildren(link);
            if (!plainText(link).equals(link.getDestination())) {
                writer.text(" (" + link.getDestination() + ")");
            }
        }

        private void renderChildren(Node parent) {
            Node child = parent.getFirstChild();
            while (child != null) {
                Node next = child.getNext();
                context.render(child);
                child = next;
            }
        }
    }

    /**
     * Фрагмент кода, целиком состоящий из адреса внутри системы, — ссылкой,
     * как {@code [адрес](адрес)} (intake-answer-links, design.md, Р2, Р3):
     * Помощник, забыв форму, пишет адрес вычитки в обратных кавычках.
     * Адрес — то, что принимает {@link ReturnTo#safe}, без пробелов
     * и с латинской буквой после «/»: {@code /} и {@code /2} в математическом
     * ответе скорее дробь, чем адрес. Внутри ссылки фрагмент остаётся кодом —
     * ссылка в ссылке недопустима.
     */
    private record InternalAddressCodeAsLink(HtmlNodeRendererContext context, String back) implements NodeRenderer {

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Code.class);
        }

        @Override
        public void render(Node node) {
            String literal = ((Code) node).getLiteral();
            HtmlWriter writer = context.getWriter();
            Optional<String> internal = insideLink(node) ? Optional.empty() : address(literal);
            if (internal.isPresent()) {
                writer.tag("a", Map.of("href", context.encodeUrl(withReturn(internal.get(), back))));
                writer.text(literal);
                writer.tag("/a");
                return;
            }
            writer.tag("code");
            writer.text(literal);
            writer.tag("/code");
        }

        private static Optional<String> address(String literal) {
            if (literal.length() < 2 || !isLatinLetter(literal.charAt(1))
                    || literal.chars().anyMatch(Character::isWhitespace)) {
                return Optional.empty();
            }
            return ReturnTo.safe(literal);
        }

        private static boolean isLatinLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static boolean insideLink(Node node) {
            for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof Link) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Картинка Markdown — своей подписью, как в отрисовке Текста Задачи. */
    private record ImageAsCaption(HtmlWriter writer) implements NodeRenderer {

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class);
        }

        @Override
        public void render(Node image) {
            writer.text(plainText(image));
        }
    }
}
