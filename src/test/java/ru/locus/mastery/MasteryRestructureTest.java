package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.NOT_MASTERED;
import static ru.locus.mastery.MasteryStatus.UNCERTAIN;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.problem.ProblemDistribution;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.NodeNotEmptyException;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.taxonomy.TopicCarriesContentException;
import ru.locus.taxonomy.TopicReceiver;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.1: долг {@code rubricator-restructure} погашен — дерево получило
 * настоящий ответ отметок Владения, а не унаследованный ноль.
 *
 * Прежде здесь стоял {@code MasteryRestructureDebtTest}, проверявший
 * исходный текст: вопросы объявлены, ответчика по существу нет. Теперь
 * ответчик есть ({@link MasteryOnNode}), и проверяется само поведение —
 * через {@link TaxonomyService}, как в {@code ProblemsGuardTheTreeTest}:
 * важно, что дерево считает, двигает и отказывает, а не что кто-то умеет
 * посчитать строки.
 *
 * <p>Сторона ADR-0036 — счёт и переезд идут по <b>всем</b> Учителям
 * и по владельцу не фильтруются — проверяется двумя Учителями: отметки
 * ставятся от лица каждого, а перестройку совершает Администратор,
 * которому ни один из них не виден. Слияние занятых ячеек при переезде —
 * ADR-0039.
 */
class MasteryRestructureTest extends IntegrationTest {

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId alice;
    private UserId bob;
    private StudentId alicesPupil;
    private StudentId bobsPupil;
    private SolutionMethodId method;

    @BeforeEach
    void twoTeachersWithPupilsAndAnAdministratorAtTheTree() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        alicesPupil = student(alice);
        bobsPupil = student(bob);
        method = library.method();
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Число считает отметки всех Учителей». */
    @Test
    void vanishingMarksAreCountedAcrossTeachers() {
        TaxonomyNodeId topic = library.topic();
        marks.put(alice, alicesPupil, topic, method, MASTERED);
        marks.put(alice, alicesPupil, topic, library.method(), NOT_MASTERED);
        marks.put(bob, bobsPupil, topic, method, UNCERTAIN);

        assertThat(taxonomy.countVanishingMarks(topic))
                .as("две отметки А и одна Б — по владельцу не фильтруется (ADR-0036)")
                .isEqualTo(3);
    }

    /** Сценарий «Отметок нет» — ноль настоящий, а не унаследованный. */
    @Test
    void nothingVanishesFromATopicWithoutMarks() {
        assertThat(taxonomy.countVanishingMarks(library.topic())).isZero();
    }

    /** Сценарий «Отметки исчезли, приёмники не задеты». */
    @Test
    void distributionRemovesMarksOfTheTopicAndLeavesReceiversAlone() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId receiver = library.topic();
        ProblemId problem = library.problem(topic, method);
        marks.put(alice, alicesPupil, topic, method, MASTERED);
        marks.put(bob, bobsPupil, topic, method, NOT_MASTERED);
        marks.put(alice, alicesPupil, receiver, method, UNCERTAIN);

        taxonomy.deleteWithDistribution(topic, new ProblemDistribution(Map.of(problem, receiver)));

        assertThatThrownBy(() -> taxonomy.node(topic)).as("Тема снята").isInstanceOf(IllegalArgumentException.class);
        assertThat(marks.findByStudent(alice, alicesPupil))
                .as("отметка снятой Темы исчезла, отметка приёмника прежняя — не слилась и не переехала")
                .containsExactly(Map.entry(new Cell(receiver, method), UNCERTAIN));
        assertThat(marks.findByStudent(bob, bobsPupil)).as("у второго Учителя отметка исчезла тоже").isEmpty();
    }

    /** Сценарий «Отметки переехали вместе с Задачами» — приёмник создаваемый потомок. */
    @Test
    void deepeningMovesMarksOntoTheCreatedChild() {
        TaxonomyNodeId topic = library.topic();
        marks.put(alice, alicesPupil, topic, method, MASTERED);
        marks.put(bob, bobsPupil, topic, method, NOT_MASTERED);

        TaxonomyNodeId child = taxonomy.create("Потомок", topic, TopicReceiver.CREATED_CHILD);

        assertThat(marks.findByStudent(alice, alicesPupil))
                .containsExactly(Map.entry(new Cell(child, method), MASTERED));
        assertThat(marks.findByStudent(bob, bobsPupil))
                .as("переезжают отметки всех Учителей")
                .containsExactly(Map.entry(new Cell(child, method), NOT_MASTERED));
        assertThat(marks.countByTopic(topic)).as("на прежней Теме отметок нет").isZero();
    }

    /** Приёмник — существующая Тема с занятой ячейкой: расхождение сливается в «владеет неуверенно» (ADR-0039). */
    @Test
    void divergingMarksMergeIntoUncertainOnAnExistingReceiver() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId elsewhere = library.topic();
        marks.put(alice, alicesPupil, topic, method, MASTERED);
        marks.put(alice, alicesPupil, elsewhere, method, NOT_MASTERED);

        taxonomy.create("Потомок", topic, TopicReceiver.existing(elsewhere));

        assertThat(marks.findByStudent(alice, alicesPupil))
                .as("одна ячейка, а не две, и значение — огрубление (ADR-0013)")
                .containsExactly(Map.entry(new Cell(elsewhere, method), UNCERTAIN));
    }

    /** Совпавшие суждения сливаются в одно без огрубления. */
    @Test
    void matchingMarksMergeIntoOneOnAnExistingReceiver() {
        TaxonomyNodeId topic = library.topic();
        TaxonomyNodeId elsewhere = library.topic();
        marks.put(alice, alicesPupil, topic, method, MASTERED);
        marks.put(alice, alicesPupil, elsewhere, method, MASTERED);

        taxonomy.create("Потомок", topic, TopicReceiver.existing(elsewhere));

        assertThat(marks.findByStudent(alice, alicesPupil))
                .containsExactly(Map.entry(new Cell(elsewhere, method), MASTERED));
    }

    /** Обычное снятие Темы с отметками отклоняется — как и с Задачами, второй рубеж за схемой. */
    @Test
    void topicCarryingMarksIsNotDeletedPlainly() {
        TaxonomyNodeId topic = library.topic();
        marks.put(bob, bobsPupil, topic, method, MASTERED);

        assertThatThrownBy(() -> taxonomy.delete(topic))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("отметки Владения")
                .hasMessageContaining("снятием с распределением");

        assertThat(taxonomy.node(topic).id()).as("Тема на месте").isEqualTo(topic);
        assertThat(marks.countByTopic(topic)).as("отметка на месте").isEqualTo(1);
    }

    /** Углубление Темы с отметками без приёмника отклоняется: с потомком отметки повисли бы на Разделе. */
    @Test
    void topicCarryingMarksIsNotDeepenedWithoutAReceiver() {
        TaxonomyNodeId topic = library.topic();
        marks.put(alice, alicesPupil, topic, method, UNCERTAIN);

        assertThatThrownBy(() -> taxonomy.create("Потомок", topic, null))
                .isInstanceOf(TopicCarriesContentException.class)
                .hasMessageContaining("отметки Владения");

        assertThat(taxonomy.children(topic)).as("потомок не создан").isEmpty();
        assertThat(marks.countByTopic(topic)).isEqualTo(1);
    }

    private StudentId student(UserId owner) {
        return students.create(owner, "Иванов Пётр-" + UUID.randomUUID());
    }
}
