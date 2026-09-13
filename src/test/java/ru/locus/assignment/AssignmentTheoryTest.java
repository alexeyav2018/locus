package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.theory.NodeTheory;
import ru.locus.theory.TheoryMaterialId;
import ru.locus.user.Role;

/**
 * Задача 5.4: теория к Заданию считается при показе по охвату
 * из текущей разметки Задач и текущего дерева (ADR-0018, ADR-0032,
 * ADR-0037); списка материалов у Задания нет.
 *
 * Обстановка — Раздел с материалом «Тригонометрия» и Тема под ним
 * с материалом «Формулы приведения», как в сценариях спеки.
 */
class AssignmentTheoryTest extends IntegrationTest {

    @Autowired
    private AssignmentService service;

    @Autowired
    private StudentService students;

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private StudentId student;
    private TaxonomyNodeId section;
    private TaxonomyNodeId topic;
    private TheoryMaterialId onTopic;
    private TheoryMaterialId onSection;
    private ProblemId problem;

    @BeforeEach
    void logInAndBuildTheTree() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        student = students.create(TestLibrary.unique("Иванов Пётр"));
        section = library.section();
        topic = library.topic(section);
        onTopic = library.material(topic, "Формулы приведения");
        onSection = library.material(section, "Тригонометрия");
        problem = library.problem(topic);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Только Темы задач». */
    @Test
    void topicsScopeShowsOnlyTheMaterialOnTheTopic() {
        Assignment issued = issue(List.of(problem), TheoryScope.TOPICS);

        List<NodeTheory> shown = service.theoryOf(issued);

        assertThat(shown).extracting(t -> t.material().id()).containsExactly(onTopic);
        assertThat(shown.getFirst().own()).isTrue();
    }

    /** Сценарий «Вместе с Разделами»: оба, каждый с именем своего узла. */
    @Test
    void topicsAndSectionsScopeShowsBothWithTheirNodes() {
        Assignment issued = issue(List.of(problem), TheoryScope.TOPICS_AND_SECTIONS);

        List<NodeTheory> shown = service.theoryOf(issued);

        assertThat(shown).extracting(t -> t.material().id()).containsExactly(onTopic, onSection);
        assertThat(shown).extracting(NodeTheory::source)
                .containsExactly(taxonomy.node(topic).name(), taxonomy.node(section).name());
    }

    /** Сценарий «Без теории»: список пуст, что бы ни лежало на узлах. */
    @Test
    void noneScopeShowsNothing() {
        Assignment issued = issue(List.of(problem), TheoryScope.NONE);

        assertThat(service.theoryOf(issued)).isEmpty();
    }

    /** Сценарий «Материал добавлен после выдачи»: теория не запоминается при выдаче. */
    @Test
    void materialAddedAfterIssueIsShown() {
        Assignment issued = issue(List.of(problem), TheoryScope.TOPICS);
        assertThat(service.theoryOf(issued)).hasSize(1);

        TheoryMaterialId later = library.material(topic, "Добавлено после выдачи");

        assertThat(service.theoryOf(issued)).extracting(t -> t.material().id()).contains(later);
    }

    /** Сценарий «Один материал по двум Темам»: материал Раздела — один раз. */
    @Test
    void sectionMaterialReachedThroughTwoTopicsIsShownOnce() {
        TaxonomyNodeId sibling = library.topic(section);
        ProblemId onSibling = library.problem(sibling);
        Assignment issued = issue(List.of(problem, onSibling), TheoryScope.TOPICS_AND_SECTIONS);

        List<NodeTheory> shown = service.theoryOf(issued);

        assertThat(shown).extracting(t -> t.material().id())
                .containsExactly(onTopic, onSection)
                .doesNotHaveDuplicates();
    }

    private Assignment issue(List<ProblemId> problems, TheoryScope scope) {
        AssignmentId id = service.issueToStudent(student, problems, LocalDate.now(), scope);
        return service.assignment(id).assignment();
    }
}
