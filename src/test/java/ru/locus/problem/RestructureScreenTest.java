package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyRepository;
import ru.locus.user.Role;

/**
 * Требование «Экран дерева ведёт перестройку» — та его часть, которой нужны
 * Задачи на Теме: форма углубления с Темой-приёмником и снятие
 * с распределением ({@code rubricator-restructure}, ADR-0007).
 *
 * Живёт в пакете Задач, а не в {@code TaxonomyScreenTest}: тесты пакета
 * дерева о Задачах не знают по тому же правилу, что и сам сервис дерева.
 * Экран — единственное у дерева место, которому область Задач известна
 * ({@code TaxonomyKnowsNothingOfProblemsTest}), и проверяется он отсюда.
 *
 * Сравнивается видимый текст и имена полей, а не разметка целиком
 * (antipatterns.md, «Сравнение разметки страницы целиком»).
 */
class RestructureScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemService problems;

    @Autowired
    private TaxonomyRepository nodes;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Углубление предлагается на Теме с Задачами». */
    @Test
    void topicWithProblemsAsksForAReceiverAndOffersTheCreatedChildByDefault() {
        TaxonomyNodeId topic = library.topic();
        library.problem(topic);
        TaxonomyNodeId other = library.topic();

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + topic.value()).body();

        assertThat(body)
                .as("форма потомка требует Тему-приёмник")
                .contains("Добавить потомка")
                .contains("Тема-приёмник")
                .contains("name=\"receiver\"");
        String receivers = select(body, "receiver");
        assertThat(receivers)
                .as("приёмником предложен создаваемый потомок")
                .contains("value=\"created\" selected");
        assertThat(receivers)
                .as("любая другая Тема дерева — тоже приёмник")
                .contains("value=\"" + other.value() + "\"");
        assertThat(receivers)
                .as("сама углубляемая Тема приёмником не предлагается: после операции она Раздел")
                .doesNotContain("value=\"" + topic.value() + "\"");
    }

    /** Сценарий «Снятие с распределением предлагается на Теме с Задачами». */
    @Test
    void topicWithProblemsOffersRemovalWithDistributionListingEveryProblem() {
        TaxonomyNodeId topic = library.topic();
        ProblemId plain = library.problem(topic);
        ProblemId captioned = problems.create("Ященко, вариант 12", ExamPart.SECOND,
                List.of(topic), List.of(library.method()), List.of(),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF));

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + topic.value()).body();

        assertThat(body)
                .as("снятие с распределением предложено вместо обычного удаления")
                .contains("Снять с распределением")
                .contains("action=\"/taxonomy/" + topic.value() + "/distribution\"")
                .doesNotContain("action=\"/taxonomy/" + topic.value() + "/deletion\"");
        assertThat(body)
                .as("каждая Задача названа номером и подписью и получает свой выбор приёмника")
                .contains("№ " + plain.value())
                .contains("name=\"destination[" + plain.value() + "]\"")
                .contains("№ " + captioned.value())
                .contains("Ященко, вариант 12")
                .contains("name=\"destination[" + captioned.value() + "]\"");
    }

    /** Сценарий «Отметок нет»: число исчезающих отметок показано до подтверждения — сегодня оно нулевое. */
    @Test
    void removalFormSaysThatNoMarksVanishToday() {
        TaxonomyNodeId topic = library.topic();
        library.problem(topic);

        String body = loggedIn(Role.ADMINISTRATOR).get("/taxonomy?node=" + topic.value()).body();

        assertThat(body).contains("Исчезающих отметок Владения нет");
    }

    /** Сценарий «Учителю перестройка не предлагается» — и на Теме с Задачами тоже. */
    @Test
    void teacherIsNotOfferedRestructuringEvenOnATopicWithProblems() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        String body = loggedIn(Role.TEACHER).get("/taxonomy?node=" + topic.value()).body();

        assertThat(body).as("Задачи Темы Учителю видны").contains("№ " + problem.value());
        assertThat(body)
                .doesNotContain("Тема-приёмник")
                .doesNotContain("name=\"receiver\"")
                .doesNotContain("Снять с распределением")
                .doesNotContain("/distribution\"")
                .doesNotContain("отметок Владения");
    }

    /**
     * Сценарий «Возврат после перестройки»: углубление с формы доходит
     * до разметки, а страница возвращается к созданному потомку — он и есть
     * затронутый узел, на нём теперь лежат Задачи.
     */
    @Test
    void deepeningFromTheFormMovesTheProblemsToTheCreatedChildAndComesBackToIt() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        Browser.Page done = loggedIn(Role.ADMINISTRATOR).postForm("/taxonomy", Map.of(
                "name", "Потомок",
                "parentId", String.valueOf(topic.value()),
                "receiver", "created"));

        assertThat(done.status()).isEqualTo(302);
        TaxonomyNodeId child = nodes.findChildren(topic).getFirst().id();
        assertThat(done.location())
                .as("параметр держит выбор, якорь — место в дереве")
                .endsWith("/taxonomy?node=" + child.value() + "#node-" + child.value());
        assertThat(problems.problem(problem).topics())
                .as("Задача переехала на созданного потомка")
                .containsExactly(child);
    }

    @Test
    void deepeningFromTheFormMovesTheProblemsToAnExistingTopic() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);
        TaxonomyNodeId receiver = library.topic();

        Browser.Page done = loggedIn(Role.ADMINISTRATOR).postForm("/taxonomy", Map.of(
                "name", "Потомок",
                "parentId", String.valueOf(topic.value()),
                "receiver", String.valueOf(receiver.value())));

        assertThat(done.status()).isEqualTo(302);
        assertThat(problems.problem(problem).topics()).containsExactly(receiver);
    }

    /** Отказ сервиса показывается на той же странице, а дерево и разметка остаются прежними. */
    @Test
    void deepeningWithoutAReceiverIsRefusedOnThePage() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        Browser.Page refused = loggedIn(Role.ADMINISTRATOR).postForm("/taxonomy", Map.of(
                "name", "Потомок",
                "parentId", String.valueOf(topic.value())));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Тему-приёмник");
        assertThat(nodes.findChildren(topic)).as("потомок не создан").isEmpty();
        assertThat(problems.problem(problem).topics()).containsExactly(topic);
    }

    /**
     * Снятие с формы: карта «Задача → приёмник» собирается из полей
     * {@code destination[<Задача>]}, Тема снимается, страница возвращается
     * на прежнего родителя — как после обычного удаления.
     */
    @Test
    void removalFromTheFormDistributesEveryProblemAndComesBackToTheParent() {
        TaxonomyNodeId parent = nodes.create(TestLibrary.unique("Алгебра"), null);
        TaxonomyNodeId topic = library.topic(parent);
        TaxonomyNodeId sibling = library.topic(parent);
        TaxonomyNodeId elsewhere = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("destination[" + first.value() + "]", String.valueOf(sibling.value()));
        form.put("destination[" + second.value() + "]", String.valueOf(elsewhere.value()));
        Browser.Page done = loggedIn(Role.ADMINISTRATOR)
                .postForm("/taxonomy/" + topic.value() + "/distribution", form);

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).endsWith("/taxonomy?node=" + parent.value() + "#node-" + parent.value());
        assertThat(nodes.findById(topic)).as("Тема снята").isEmpty();
        assertThat(problems.problem(first).topics()).containsExactly(sibling);
        assertThat(problems.problem(second).topics()).containsExactly(elsewhere);
    }

    /**
     * Родитель, у которого снимаемая Тема — единственный потомок, предложен
     * приёмником, хотя сейчас он Раздел: после снятия он лист, и Задачи
     * «поднимаются на родителя» (ADR-0007) через тот же общий выбор.
     */
    @Test
    void parentLeftWithoutChildrenIsOfferedAndAcceptedAsAReceiver() {
        TaxonomyNodeId parent = nodes.create(TestLibrary.unique("Алгебра"), null);
        TaxonomyNodeId topic = library.topic(parent);
        ProblemId problem = library.problem(topic);
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        String body = administrator.get("/taxonomy?node=" + topic.value()).body();
        assertThat(select(body, "destination[" + problem.value() + "]"))
                .as("родитель есть в списке приёмников формы снятия, а снимаемая Тема — нет")
                .contains("value=\"" + parent.value() + "\"")
                .doesNotContain("value=\"" + topic.value() + "\"");

        Browser.Page done = administrator.postForm("/taxonomy/" + topic.value() + "/distribution",
                Map.of("destination[" + problem.value() + "]", String.valueOf(parent.value())));

        assertThat(done.status()).isEqualTo(302);
        assertThat(problems.problem(problem).topics()).containsExactly(parent);
    }

    /** Задача без выбранного приёмника — отказ на странице, Тема и разметка на месте. */
    @Test
    void removalWithAnUnchosenReceiverIsRefusedOnThePage() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId elsewhere = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("destination[" + first.value() + "]", String.valueOf(elsewhere.value()));
        form.put("destination[" + second.value() + "]", "");
        Browser.Page refused = loggedIn(Role.ADMINISTRATOR)
                .postForm("/taxonomy/" + topic.value() + "/distribution", form);

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("№ " + second.value()).contains("не указана Тема-приёмник");
        assertThat(nodes.findById(topic)).as("Тема на месте").isPresent();
        assertThat(problems.problem(first).topics()).as("разметка не тронута").containsExactly(topic);
    }

    /** Неполная карта отклоняется сервисом Задач; отказ показан, транзакция откачена. */
    @Test
    void removalLeavingAProblemUnnamedIsRefusedAndNothingChanges() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId elsewhere = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        Browser.Page refused = loggedIn(Role.ADMINISTRATOR).postForm(
                "/taxonomy/" + topic.value() + "/distribution",
                Map.of("destination[" + first.value() + "]", String.valueOf(elsewhere.value())));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("неполно").contains("№ " + second.value());
        assertThat(nodes.findById(topic)).isPresent();
        assertThat(problems.problem(first).topics()).containsExactly(topic);
    }

    /**
     * Разметка одного списка выбора — от его имени до закрывающего тега.
     * Идентификатор узла встречается на странице и в других полях (скрытый
     * родитель, список «Перенести под»), поэтому проверять состав приёмников
     * можно только внутри самого списка.
     */
    private static String select(String body, String name) {
        int from = body.indexOf("name=\"" + name + "\"");
        assertThat(from).as("список %s есть на странице", name).isNotNegative();
        int to = body.indexOf("</select>", from);
        assertThat(to).isNotNegative();
        return body.substring(from, to);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
