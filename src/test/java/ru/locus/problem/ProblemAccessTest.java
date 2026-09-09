package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Требование «Ведёт Задачи только Администратор, читают все вошедшие».
 *
 * Отказ проверяется обращением по прямому адресу, а не отсутствием кнопки
 * в разметке: доступность операции определяется правами, а не тем, показана
 * ли на неё форма.
 *
 * Задачи заводятся через репозиторий: тесту нужно подготовить обстановку,
 * а не проверить права на её подготовку.
 */
class ProblemAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemRepository problems;

    /** Сценарий «Учитель читает библиотеку». */
    @Test
    void teacherOpensAProblemWithItsMarkupAndFiles() {
        ProblemId problem = library.problem(library.topic());

        Browser.Page page = loggedIn(Role.TEACHER).get("/problems/" + problem.value());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Задача № " + problem.value())
                .contains("PDF условия")
                .contains("PDF решения");
    }

    /** Сценарий «Учитель пытается изменить библиотеку». */
    @Test
    void teacherIsRefusedEveryEditingOperation() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);
        SolutionMethodId method = problems.findById(problem).orElseThrow().methods().get(0);
        Browser teacher = loggedIn(Role.TEACHER);

        assertThat(teacher.postMultipart("/problems",
                Map.of("part", "FIRST", "topics", String.valueOf(topic.value()),
                        "methodIds", String.valueOf(method.value())),
                Map.of("condition", TestLibrary.pdf(), "solution", TestLibrary.pdf())).status())
                .as("заведение Задачи")
                .isEqualTo(403);
        assertThat(teacher.postForm("/problems/" + problem.value(),
                Map.of("part", "FIRST", "topics", String.valueOf(topic.value()),
                        "methodIds", String.valueOf(method.value()))).status())
                .as("правка разметки")
                .isEqualTo(403);
        assertThat(teacher.postMultipart("/problems/" + problem.value() + "/solution",
                Map.of(), Map.of("solution", TestLibrary.pdf())).status())
                .as("замена файла")
                .isEqualTo(403);
        assertThat(teacher.postForm("/problems/" + problem.value() + "/deletion", Map.of()).status())
                .as("удаление")
                .isEqualTo(403);

        assertThat(problems.findById(problem)).as("библиотека осталась прежней").isPresent();
        assertThat(problems.findByTopic(topic)).extracting(Problem::id).containsExactly(problem);
    }

    /** Сценарий «Невошедший к Задачам не допускается». */
    @Test
    void withoutLoginNeitherMarkupNorFilesAreShown() {
        ProblemId problem = library.problem(library.topic());

        Browser.Page page = new Browser(port).get("/problems/" + problem.value());

        assertThat(page.redirectsTo("/login")).as("обращение приводит к форме входа").isTrue();
        assertThat(page.body()).doesNotContain("PDF решения");
    }

    @Test
    void administratorCreatesAProblemThroughTheForm() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();

        Browser.Page created = loggedIn(Role.ADMINISTRATOR).postMultipart("/problems",
                Map.of("caption", "Ященко, вариант 12", "part", "SECOND",
                        "topics", String.valueOf(topic.value()),
                        "methodIds", String.valueOf(method.value())),
                Map.of("condition", TestLibrary.pdf(), "solution", TestLibrary.pdf()));

        assertThat(created.status()).as("операция выполнена, ответ — переадресация").isEqualTo(302);
        List<Problem> onTopic = problems.findByTopic(topic);
        assertThat(onTopic).hasSize(1);
        assertThat(onTopic.get(0).caption()).isEqualTo("Ященко, вариант 12");
        assertThat(onTopic.get(0).part()).isEqualTo(ExamPart.SECOND);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
