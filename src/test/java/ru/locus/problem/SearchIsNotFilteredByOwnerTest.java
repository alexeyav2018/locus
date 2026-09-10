package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Граница общего и личного со стороны поиска: найденное не зависит от того,
 * кто ищет.
 *
 * Задачи — общая библиотека, и фильтр по владельцу к ней не применяется
 * (ADR-0027, antipatterns.md, первый пункт). Отсутствие такого фильтра
 * в коде уже сторожит {@link OwnerIsUnknownToProblemsTest}: владельца
 * в область Задач невозможно передать. Здесь проверяется то же самое
 * с другой стороны — не по устройству кода, а по тому, что видят люди:
 * фильтр мог бы приехать и не параметром метода, а из сессии.
 *
 * Проверка идёт через настоящий вход двумя разными Учителями, а не вызовом
 * сервиса: забытый фильтр не падает, а тихо показывает меньше — и заметить
 * это можно, только сравнив то, что по одинаковым условиям видят разные
 * люди.
 *
 * Второй тест — про обратную ошибку: библиотека общая для вошедших,
 * а не для всех. Открытый наружу поиск отдал бы содержимое библиотеки
 * любому, кто знает адрес.
 */
class SearchIsNotFilteredByOwnerTest extends IntegrationTest {

    /** Номер Задачи в строке результата — то, по чему списки и сравниваются. */
    private static final Pattern FOUND = Pattern.compile("№ (\\d+)");

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    /**
     * Сценарий «Задачи, заведённые другим Пользователем» — со стороны поиска.
     *
     * Условия заданы все сразу и непустыми: фильтр по владельцу, приложенный
     * к отбору, скорее всего сложился бы с ними одним и тем же способом,
     * и поиск без условий его бы не показал.
     */
    @Test
    void twoDifferentTeachersFindTheSameProblems() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();
        ProblemId problem = library.problem(
                List.of(topic), List.of(method), List.of(characteristic), ExamPart.SECOND);
        String conditions = "/problems?node=" + section.value()
                + "&method=" + method.value() + "&methodMode=ANY"
                + "&characteristic=" + characteristic.value() + "&characteristicMode=ANY"
                + "&part=SECOND";

        List<String> first = found(loggedIn(Role.TEACHER).get(conditions).body());
        List<String> second = found(loggedIn(Role.TEACHER).get(conditions).body());

        assertThat(first)
                .as("библиотека общая: по владельцу поиск не фильтруется")
                .contains(String.valueOf(problem.value()));
        assertThat(second).containsExactlyElementsOf(first);
    }

    /** Библиотека общая для вошедших, а не открытая наружу. */
    @Test
    void visitorWhoIsNotLoggedInGetsTheLoginFormAndNotTheResults() {
        ProblemId problem = library.problem(library.topic());

        Browser.Page page = new Browser(port).get("/problems");

        assertThat(page.redirectsTo("/login")).as("обращение приводит к форме входа").isTrue();
        assertThat(page.body()).doesNotContain("№ " + problem.value());
    }

    private List<String> found(String page) {
        Matcher number = FOUND.matcher(page);
        return number.results().map(match -> match.group(1)).toList();
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
