package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.taxonomy.NodeNotEmptyException;
import ru.locus.taxonomy.TaxonomyNode;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.taxonomy.TopicCarriesContentException;
import ru.locus.user.Role;

/**
 * Требования рубрикатора «Удаляется только пустой узел» и «Создание узла» —
 * та их часть, которая до появления Задач выполнялась тождественно и потому
 * ничего не проверяла.
 *
 * Долг записан прямо в спеке дерева и погашен здесь: проверка отсутствия
 * Задач на удаляемой Теме теперь <b>действует</b>, а Тему с Задачами нельзя
 * ещё и углубить.
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
     * при этом не выдано.
     */
    @Test
    void topicCarryingProblemsCannotBeDeepened() {
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);

        assertThatThrownBy(() -> taxonomy.create("Потомок", topic))
                .isInstanceOf(TopicCarriesContentException.class)
                .hasMessageContaining("Задачи")
                .as("отказ называет, что перенос ещё не построен")
                .hasMessageContaining("Темы-приёмника");

        assertThat(taxonomy.children(topic)).as("узел не создан").isEmpty();
        assertThat(taxonomy.node(topic).isTopic()).as("узел остался Темой").isTrue();
        assertThat(problems.problemsOf(topic)).extracting(Problem::id)
                .as("Задачи Темы остаются на месте")
                .containsExactly(problem);
    }

    /** Сценарий «Потомок у Темы без Задач». */
    @Test
    void topicWithoutProblemsIsDeepenedAndBecomesASection() {
        TaxonomyNodeId topic = library.topic();

        taxonomy.create("Потомок", topic);

        TaxonomyNode wasTopic = taxonomy.node(topic);
        assertThat(wasTopic.isSection()).as("вид прежней Темы — Раздел").isTrue();
    }
}
