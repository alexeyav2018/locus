package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.NOT_MASTERED;
import static ru.locus.mastery.MasteryStatus.UNCERTAIN;
import static ru.locus.mastery.MasteryStatus.UNKNOWN;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 3.1: хранение отметок — перезапись без истории, «неизвестно»
 * как отсутствие строки, и главное — каждый метод с владельцем отвечает
 * только ему.
 *
 * Как в {@link ru.locus.work.StudentWorkRepositoryTest}, изоляция
 * проверяется двумя владельцами. Сверх того — обе стороны ADR-0036:
 * методы без владельца считают и двигают отметки ОБОИХ Учителей, — и то,
 * что держит схема: отметка чужому Ученику не вставляется, ключу
 * {@code fk_mastery_student} не на что сослаться. Слияние при переезде
 * (ADR-0039) проверяется по одному случаю на исход и всеми сразу
 * в одном вызове: три запроса {@link MasteryRepository#rehomeTopic}
 * должны сработать в правильном порядке на смешанной картине, а не только
 * на однородной.
 */
class MasteryRepositoryTest extends IntegrationTest {

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
    private StudentId pupil;
    private TaxonomyNodeId topic;
    private SolutionMethodId method;

    @BeforeEach
    void twoTeachersAStudentAndACell() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        pupil = student(alice);
        topic = library.topic();
        method = library.method();
    }

    @Test
    void putCreatesThenOverwritesWithoutHistory() {
        marks.put(alice, pupil, topic, method, NOT_MASTERED);
        assertThat(marks.findByStudent(alice, pupil)).containsExactly(Map.entry(new Cell(topic, method), NOT_MASTERED));

        marks.put(alice, pupil, topic, method, MASTERED);
        assertThat(marks.findByStudent(alice, pupil))
                .as("перезапись, а не вторая строка (ADR-0012)")
                .containsExactly(Map.entry(new Cell(topic, method), MASTERED));
        assertThat(marks.countByStudent(alice, pupil)).isEqualTo(1);
    }

    /** «Неизвестно» — отсутствие строки: простановка {@code UNKNOWN} удаляет её (ADR-0039). */
    @Test
    void unknownRemovesTheRow() {
        marks.put(alice, pupil, topic, method, MASTERED);

        marks.put(alice, pupil, topic, method, UNKNOWN);

        assertThat(marks.findByStudent(alice, pupil)).isEmpty();
        assertThat(marks.countByStudent(alice, pupil)).isZero();
        assertThat(marks.countByTopic(topic)).as("строки нет — считать нечего").isZero();
    }

    @Test
    void unknownOnAnEmptyCellIsHarmless() {
        marks.put(alice, pupil, topic, method, UNKNOWN);

        assertThat(marks.findByStudent(alice, pupil)).isEmpty();
    }

    @Test
    void marksOfStudentAreFoundAndCountedForTheOwnerOnly() {
        SolutionMethodId other = library.method();
        marks.put(alice, pupil, topic, method, MASTERED);
        marks.put(alice, pupil, topic, other, UNCERTAIN);

        assertThat(marks.findByStudent(alice, pupil)).containsOnly(
                Map.entry(new Cell(topic, method), MASTERED),
                Map.entry(new Cell(topic, other), UNCERTAIN));
        assertThat(marks.countByStudent(alice, pupil)).isEqualTo(2);

        assertThat(marks.findByStudent(bob, pupil)).as("чужой Ученик — пусто").isEmpty();
        assertThat(marks.countByStudent(bob, pupil)).as("чужой Ученик — ноль").isZero();
    }

    /** Отметка чужому Ученику не вставляется — ключу {@code fk_mastery_student} не на что сослаться. */
    @Test
    void markOnAnotherOwnersStudentIsRejectedBySchema() {
        assertThatThrownBy(() -> marks.put(bob, pupil, topic, method, MASTERED))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(marks.findByStudent(alice, pupil)).isEmpty();
        assertThat(marks.countByTopic(topic)).isZero();
    }

    /** Вопросы дерева и словаря считают отметки ВСЕХ Учителей (ADR-0036). */
    @Test
    void topicAndMethodCountsSpanAllOwners() {
        StudentId theirs = student(bob);
        TaxonomyNodeId otherTopic = library.topic();
        SolutionMethodId otherMethod = library.method();
        marks.put(alice, pupil, topic, method, MASTERED);
        marks.put(bob, theirs, topic, method, NOT_MASTERED);
        marks.put(alice, pupil, otherTopic, method, MASTERED);
        marks.put(bob, theirs, topic, otherMethod, MASTERED);

        assertThat(marks.countByTopic(topic)).as("двое на Теме, у одного два Метода").isEqualTo(3);
        assertThat(marks.countByTopic(otherTopic)).isEqualTo(1);
        assertThat(marks.countByMethod(method)).as("двое на Методе, у одного две Темы").isEqualTo(3);
        assertThat(marks.countByMethod(otherMethod)).isEqualTo(1);
    }

    @Test
    void rehomeMovesMarksOfAllOwnersToAnEmptyReceiver() {
        StudentId theirs = student(bob);
        TaxonomyNodeId receiver = library.topic();
        marks.put(alice, pupil, topic, method, MASTERED);
        marks.put(bob, theirs, topic, method, NOT_MASTERED);

        marks.rehomeTopic(topic, receiver);

        assertThat(marks.countByTopic(topic)).isZero();
        assertThat(marks.findByStudent(alice, pupil)).containsExactly(Map.entry(new Cell(receiver, method), MASTERED));
        assertThat(marks.findByStudent(bob, theirs)).containsExactly(Map.entry(new Cell(receiver, method), NOT_MASTERED));
    }

    /** Совпавшие суждения сливаются в одно — остаётся одна строка с тем же значением (ADR-0039). */
    @Test
    void rehomeMergesEqualJudgementsIntoOne() {
        TaxonomyNodeId receiver = library.topic();
        marks.put(alice, pupil, topic, method, MASTERED);
        marks.put(alice, pupil, receiver, method, MASTERED);

        marks.rehomeTopic(topic, receiver);

        assertThat(marks.findByStudent(alice, pupil)).containsExactly(Map.entry(new Cell(receiver, method), MASTERED));
        assertThat(marks.countByTopic(topic)).isZero();
    }

    /** Разошедшиеся суждения огрубляются до «владеет неуверенно» (ADR-0039). */
    @Test
    void rehomeMergesDifferentJudgementsIntoUncertain() {
        TaxonomyNodeId receiver = library.topic();
        marks.put(alice, pupil, topic, method, MASTERED);
        marks.put(alice, pupil, receiver, method, NOT_MASTERED);

        marks.rehomeTopic(topic, receiver);

        assertThat(marks.findByStudent(alice, pupil)).containsExactly(Map.entry(new Cell(receiver, method), UNCERTAIN));
        assertThat(marks.countByTopic(topic)).isZero();
    }

    /**
     * Все три исхода в одном вызове: свободная ячейка переезжает, совпавшая
     * остаётся, разошедшаяся становится «неуверенно», — а отметки на
     * приёмнике без пары и на соседней Теме не меняются. Именно смешанная
     * картина проверяет порядок трёх запросов: перевешивание раньше
     * удаления занятых упало бы на ключе.
     */
    @Test
    void rehomeHandlesAMixedPictureInOneCall() {
        TaxonomyNodeId receiver = library.topic();
        TaxonomyNodeId neighbour = library.topic();
        SolutionMethodId free = library.method();
        SolutionMethodId equal = library.method();
        SolutionMethodId differing = library.method();
        SolutionMethodId receiverOnly = library.method();
        marks.put(alice, pupil, topic, free, MASTERED);
        marks.put(alice, pupil, topic, equal, NOT_MASTERED);
        marks.put(alice, pupil, receiver, equal, NOT_MASTERED);
        marks.put(alice, pupil, topic, differing, MASTERED);
        marks.put(alice, pupil, receiver, differing, UNCERTAIN);
        marks.put(alice, pupil, receiver, receiverOnly, MASTERED);
        marks.put(alice, pupil, neighbour, free, NOT_MASTERED);

        marks.rehomeTopic(topic, receiver);

        assertThat(marks.findByStudent(alice, pupil)).containsOnly(
                Map.entry(new Cell(receiver, free), MASTERED),
                Map.entry(new Cell(receiver, equal), NOT_MASTERED),
                Map.entry(new Cell(receiver, differing), UNCERTAIN),
                Map.entry(new Cell(receiver, receiverOnly), MASTERED),
                Map.entry(new Cell(neighbour, free), NOT_MASTERED));
        assertThat(marks.countByTopic(topic)).isZero();
    }

    @Test
    void deleteByTopicLeavesNeighbouringTopicsAlone() {
        StudentId theirs = student(bob);
        TaxonomyNodeId neighbour = library.topic();
        marks.put(alice, pupil, topic, method, MASTERED);
        marks.put(bob, theirs, topic, method, MASTERED);
        marks.put(alice, pupil, neighbour, method, NOT_MASTERED);

        marks.deleteByTopic(topic);

        assertThat(marks.countByTopic(topic)).as("отметки обоих Учителей исчезли").isZero();
        assertThat(marks.findByStudent(alice, pupil)).containsExactly(Map.entry(new Cell(neighbour, method), NOT_MASTERED));
        assertThat(marks.findByStudent(bob, theirs)).isEmpty();
    }

    private StudentId student(UserId owner) {
        return students.create(owner, "Иванов Пётр-" + UUID.randomUUID());
    }
}
