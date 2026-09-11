package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.NodeContent;
import ru.locus.taxonomy.NodeNotEmptyException;
import ru.locus.taxonomy.ReceiverIsNotATopicException;
import ru.locus.taxonomy.TaxonomyNode;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.taxonomy.TopicCarriesContentException;
import ru.locus.taxonomy.TopicReceiver;
import ru.locus.user.Role;

/**
 * Требования рубрикатора «Удаляется только пустой узел» и «Создание узла» —
 * та их часть, которая до появления Задач выполнялась тождественно и потому
 * ничего не проверяла.
 *
 * Долг записан прямо в спеке дерева и погашен здесь: проверка отсутствия
 * Задач на удаляемой Теме теперь <b>действует</b>, а Тема с Задачами
 * углубляется только с Темой-приёмником, на которую Задачи переезжают
 * ({@code rubricator-restructure}, ADR-0007).
 *
 * Проверяется через {@link TaxonomyService}, а не через реализацию вопроса:
 * важно, что дерево отказывает, а не что кто-то умеет посчитать Задачи.
 */
class ProblemsGuardTheTreeTest extends IntegrationTest {

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private ProblemService problems;

    @Autowired
    private TestLibrary library;

    /** Ответчики дерева — тем же списком, каким их собирает {@link TaxonomyService}. */
    @Autowired
    private List<NodeContent> content;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Удаление Темы с Задачами». */
    @Test
    void topicCarryingProblemsIsNotDeleted() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        assertThatThrownBy(() -> taxonomy.delete(topic))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("Задачи");

        assertThat(taxonomy.node(topic)).as("Тема на месте").isNotNull();
        assertThat(problems.problem(problem)).as("её Задачи на месте").isNotNull();
    }

    /** Сценарий «Удаление Темы, освободившейся от Задач». */
    @Test
    void topicFreedFromItsLastProblemIsDeleted() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        problems.delete(problem);

        assertThatCode(() -> taxonomy.delete(topic))
                .as("условие перестало выполняться — удаление разрешено")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> taxonomy.node(topic)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Сценарий «Пустой узел удалён»: без Задач ничего не изменилось. */
    @Test
    void emptyTopicIsStillDeletedAsBefore() {
        TaxonomyNodeId topic = library.topic();

        assertThatCode(() -> taxonomy.delete(topic)).doesNotThrowAnyException();
    }

    /**
     * Сценарий «Потомок у Темы с Задачами».
     *
     * Молча выполненная операция дала бы Задачи на Разделе: в дереве они
     * больше не находятся, в статистике не участвуют, и никакой ошибки
     * при этом не выдано. Отказ называет выход — Тему-приёмник.
     */
    @Test
    void topicCarryingProblemsIsNotDeepenedWithoutAReceiver() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        assertThatThrownBy(() -> taxonomy.create("Потомок", topic))
                .isInstanceOf(TopicCarriesContentException.class)
                .hasMessageContaining("Задачи")
                .as("отказ называет выход — Тему-приёмник")
                .hasMessageContaining("Тему-приёмник");

        assertThat(taxonomy.children(topic)).as("узел не создан").isEmpty();
        assertThat(taxonomy.node(topic).isTopic()).as("узел остался Темой").isTrue();
        assertThat(problems.problemsOf(topic)).extracting(Problem::id)
                .as("Задачи Темы остаются на месте")
                .containsExactly(problem);
    }

    /** Сценарий «Задачи переехали на созданного потомка». */
    @Test
    void problemsMoveToTheCreatedChildWhenItIsTheReceiver() {
        TaxonomyNodeId topic = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        TaxonomyNodeId child = taxonomy.create("Потомок", topic, TopicReceiver.CREATED_CHILD);

        assertThat(taxonomy.children(topic)).extracting(TaxonomyNode::id).containsExactly(child);
        assertThat(problems.problemsOf(child)).extracting(Problem::id)
                .as("все Задачи прежней Темы размечены потомком")
                .containsExactlyInAnyOrder(first, second);
        assertThat(problems.problemsOf(topic)).as("прежняя Тема Задач не несёт").isEmpty();
        assertThat(taxonomy.node(topic).isSection()).as("вид прежней Темы — Раздел").isTrue();
    }

    /** Сценарий «Приёмник — существующая Тема в другом месте дерева». */
    @Test
    void problemsMoveToAnExistingTopicElsewhereWhenItIsTheReceiver() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId elsewhere = library.topic(library.section());
        ProblemId problem = library.problem(topic);

        TaxonomyNodeId child = taxonomy.create("Потомок", topic, TopicReceiver.existing(elsewhere));

        assertThat(problems.problemsOf(elsewhere)).extracting(Problem::id).containsExactly(problem);
        assertThat(problems.problemsOf(child)).as("созданный потомок Задач не несёт").isEmpty();
        assertThat(problems.problemsOf(topic)).isEmpty();
    }

    /** Сценарий «Прочая разметка Задачи не задета». */
    @Test
    void theRestOfTheMarkupSurvivesTheMove() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId other = library.topic();
        SolutionMethodId one = library.method();
        SolutionMethodId two = library.method();
        CharacteristicId trait = library.characteristic();
        ProblemId id = library.problem(List.of(topic, other), List.of(one, two), List.of(trait), ExamPart.FIRST);

        TaxonomyNodeId child = taxonomy.create("Потомок", topic, TopicReceiver.CREATED_CHILD);

        Problem moved = problems.problem(id);
        assertThat(moved.topics()).containsExactlyInAnyOrder(child, other);
        assertThat(moved.methods()).containsExactlyInAnyOrder(one, two);
        assertThat(moved.characteristics()).containsExactly(trait);
        assertThat(moved.part()).isEqualTo(ExamPart.FIRST);
    }

    /** Сценарий «Задача уже размечена приёмником». */
    @Test
    void problemAlreadyMarkedWithTheReceiverCarriesItOnce() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId receiver = library.topic();
        ProblemId id = library.problem(List.of(topic, receiver), List.of(library.method()), List.of(),
                ExamPart.SECOND);

        taxonomy.create("Потомок", topic, TopicReceiver.existing(receiver));

        assertThat(problems.problem(id).topics()).containsExactly(receiver);
    }

    /**
     * Сценарий «Приёмником указан узел, перестающий быть Темой»: отказ
     * приходит до создания потомка, и разметка не трогается.
     */
    @Test
    void refusedReceiverLeavesBothTheTreeAndTheMarkupUntouched() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId section = library.section();
        ProblemId problem = library.problem(topic);

        assertThatThrownBy(() -> taxonomy.create("Потомок", topic, TopicReceiver.existing(topic)))
                .as("сама углубляемая Тема")
                .isInstanceOf(ReceiverIsNotATopicException.class);
        assertThatThrownBy(() -> taxonomy.create("Потомок", topic, TopicReceiver.existing(section)))
                .as("Раздел")
                .isInstanceOf(ReceiverIsNotATopicException.class);

        assertThat(taxonomy.children(topic)).as("потомок не создан").isEmpty();
        assertThat(problems.problemsOf(topic)).extracting(Problem::id)
                .as("Задачи не переехали")
                .containsExactly(problem);
        assertThat(problems.problemsOf(section)).isEmpty();
    }

    /** Сценарий «Потомок у Темы без Задач». */
    @Test
    void topicWithoutProblemsIsDeepenedAndBecomesASection() {
        TaxonomyNodeId topic = library.topic();

        taxonomy.create("Потомок", topic);

        TaxonomyNode wasTopic = taxonomy.node(topic);
        assertThat(wasTopic.isSection()).as("вид прежней Темы — Раздел").isTrue();
    }

    /**
     * Перестройка ({@code rubricator-restructure}): переезд, запрошенный
     * дереву тем же способом, каким его запрашивает {@code TaxonomyService}, —
     * у всех ответчиков списком, не зная ни одного по имени, — доходит
     * до разметки Задач. Что само дерево зовёт его при углублении, проверяют
     * сценарии углубления с приёмником выше в этом же классе.
     */
    @Test
    void moveAskedByTheTreeReachesTheMarkupOfProblems() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        ProblemId problem = library.problem(from);

        content.forEach(answerer -> answerer.moveTopicContent(from, to));

        assertThat(problems.problemsOf(to)).extracting(Problem::id).containsExactly(problem);
        assertThat(problems.problemsOf(from)).isEmpty();
    }

    /**
     * Исчезающего у Задач нет: они распределяются, а не пропадают. Ответ
     * по существу придёт с отметками Владения ({@code MasteryRestructureDebtTest}).
     */
    @Test
    void problemsCountNothingAsVanishing() {
        TaxonomyNodeId topic = library.topic();
        library.problem(topic);

        assertThat(content).allSatisfy(answerer -> assertThat(answerer.countVanishing(topic)).isZero());
    }
}
