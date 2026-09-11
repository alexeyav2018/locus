package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Задача 6.1: перестройка дерева сильнее заморозки Задачи (ADR-0034).
 *
 * Заморожена правка рукой: {@code edit}, {@code replaceCondition},
 * {@code replaceSolution} и {@code delete} зовут {@code refuseUnlessUnused}
 * и откажут, как только у Задачи появится первое использование. Перестройка
 * дерева — {@code rehomeTopic} и {@code distribute} — эту проверку
 * <b>не зовёт</b>: она не правит разметку, а двигает ось, вдоль которой
 * разметка задана; Задача остаётся на своём месте предметно.
 *
 * Сегодня разница невидима: реализаций {@link ProblemUsage} нет ни одной,
 * и проверка выполняется тождественно, куда её ни поставь. Она станет
 * видимой, когда {@code assignments} заведёт первую реализацию: Задача,
 * вошедшая в Задание, заморозится — и если бы перестройка звала ту же
 * проверку, то вместе с Задачей заморозилось бы дерево. Тему с выданной
 * Задачей нельзя было бы ни углубить, ни снять, и навсегда — Задания
 * не отменяются. Рубрикатор перестал бы перестраиваться ровно тогда,
 * когда им начали пользоваться.
 *
 * Обратная ошибка тоже тихая: убери проверку из {@code edit} — и правка
 * выданной Задачи изменит основание чужих отметок задним числом (ADR-0030).
 * Поэтому проверяются обе стороны, и по исходному тексту: сегодня ни один
 * сценарий на живой базе разницы не покажет, а «когда-нибудь дописать
 * реализацию {@code ProblemUsage} для теста» — это и есть тот долг, который
 * забывается. Образец — {@link ProblemUsageDebtTest}.
 */
class RestructureBypassesFreezeTest {

    @ParameterizedTest
    @ValueSource(strings = {"edit", "replaceCondition", "replaceSolution", "delete"})
    void handEditingIsFrozenAfterFirstUse(String method) {
        assertThat(bodyOf(method))
                .as("правка рукой (%s) обязана отказать использованной Задаче — ADR-0030", method)
                .contains("refuseUnlessUnused(");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rehomeTopic", "distribute"})
    void restructuringIsNotFrozen(String method) {
        assertThat(bodyOf(method))
                .as("перестройка (%s) сильнее заморозки — ADR-0034; проверка здесь заморозила бы дерево",
                        method)
                .doesNotContain("refuseUnlessUnused(");
    }

    /**
     * Отсутствие вызова легко принять за пропуск и «починить». Пояснение,
     * почему его нет, должно стоять у самого метода и вести к решению.
     */
    @ParameterizedTest
    @ValueSource(strings = {"rehomeTopic", "distribute"})
    void theReasonIsRecordedNextToTheMethod(String method) {
        assertThat(javadocOf(method))
                .as("у %s записано, почему проверка не зовётся, со ссылкой на ADR-0034", method)
                .contains("refuseUnlessUnused")
                .contains("ADR-0034");
    }

    /**
     * Перестройка — отдельный названный путь, а не {@code edit} с флагом
     * «не проверять»: флаг однажды оказался бы выставлен из формы правки,
     * и заморозка перестала бы работать молча.
     */
    @Test
    void restructuringDoesNotReuseHandEditing() {
        for (String method : List.of("rehomeTopic", "distribute")) {
            assertThat(bodyOf(method))
                    .as("%s не зовёт правку рукой в обход её проверки", method)
                    .doesNotContain("edit(")
                    .doesNotContain("replaceMarkup(");
        }
    }

    private static String bodyOf(String method) {
        String source = source();
        int open = source.indexOf('{', declarationOf(source, method));
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("тело метода " + method + " не закрыто");
    }

    private static String javadocOf(String method) {
        String source = source();
        int declaration = declarationOf(source, method);
        int start = source.lastIndexOf("/**", declaration);
        int end = source.indexOf("*/", start);
        assertThat(start).as("у %s есть javadoc", method).isNotNegative();
        return source.substring(start, end);
    }

    private static int declarationOf(String source, String method) {
        int at = source.indexOf("void " + method + "(");
        assertThat(at).as("в ProblemService объявлен метод %s", method).isNotNegative();
        return at;
    }

    private static String source() {
        Path source = Path.of("src/main/java", ProblemService.class.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст ProblemService найден").exists();
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
