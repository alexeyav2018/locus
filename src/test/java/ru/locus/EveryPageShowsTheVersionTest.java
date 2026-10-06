package ru.locus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Требование «Версия системы видна на каждой странице» (interface-navigation,
 * ADR-0043): страница без подвала не появляется. Забытый подвал — тихая ошибка:
 * страница работает, а по обращению «что у вас за версия» ответить нечем.
 *
 * Полностраничным считается шаблон, берущий {@code fragments/shell :: head}:
 * вставки вроде {@code taxonomy/branches.html} головы не берут и подвала
 * не требуют, а сам {@code shell.html} подвал объявляет.
 */
class EveryPageShowsTheVersionTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Test
    void everyFullPageTemplateIncludesTheFooter() throws IOException {
        List<Path> pages;
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            pages = files.filter(path -> path.toString().endsWith(".html"))
                    .filter(path -> !path.endsWith("fragments/shell.html"))
                    .filter(EveryPageShowsTheVersionTest::takesTheHead)
                    .toList();
        }

        assertThat(pages).as("найдены полностраничные шаблоны").hasSizeGreaterThan(10);
        assertThat(pages.stream().filter(page -> !includesTheFooter(page)).toList())
                .as("шаблоны без подвала: добавьте <th:block th:replace=\"~{fragments/shell :: footer}\"/> перед </body>")
                .isEmpty();
    }

    @Test
    void theLoginFormAndTheErrorPageAreAmongThePagesChecked() throws IOException {
        assertThat(takesTheHead(TEMPLATES.resolve("login.html"))).isTrue();
        assertThat(takesTheHead(TEMPLATES.resolve("error.html"))).isTrue();
    }

    private static boolean takesTheHead(Path page) {
        return read(page).contains("fragments/shell :: head");
    }

    private static boolean includesTheFooter(Path page) {
        return read(page).contains("fragments/shell :: footer");
    }

    private static String read(Path page) {
        try {
            return Files.readString(page);
        } catch (IOException e) {
            throw new IllegalStateException(page.toString(), e);
        }
    }
}
