package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.NOT_MASTERED;
import static ru.locus.mastery.MasteryStatus.UNCERTAIN;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.problem.ExamPart;
import ru.locus.student.StudentId;
import ru.locus.student.StudentNotFoundException;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 2.2: {@link MasteryService#overviewOf} — распределение по дереву
 * целиком, таблица по Методу и перечень пробелов одного Ученика
 * (`mastery-views`, design.md).
 *
 * Ячейки Темы — Методы из разметки Задач ∪ Методы с суждением на этой
 * Теме; распределение Раздела — сумма поддерева, распределение Метода —
 * сумма по всем Темам, где он среди ячеек. Единого значения нет нигде —
 * только четыре числа {@link Distribution} (ADR-0013,
 * {@link NoSingleMasteryValueTest}).
 */
class MasteryOverviewTest extends IntegrationTest {

    @Autowired
    private MasteryService service;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;
    private StudentId student;

    @BeforeEach
    void logInAsATeacherWithAPupil() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
        student = students.create(owner, TestLibrary.unique("Иванов Пётр"));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Ячейки Темы — из разметки»: четыре Метода, два с суждением, два без. */
    @Test
    void topicDistributionCountsMarkupCellsWithJudgementsAndUnknown() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId m1 = library.method();
        SolutionMethodId m2 = library.method();
        SolutionMethodId m3 = library.method();
        SolutionMethodId m4 = library.method();
        library.problem(List.of(topic), List.of(m1, m2, m3, m4), List.of(), ExamPart.SECOND);
        marks.put(owner, student, topic, m1, MASTERED);
        marks.put(owner, student, topic, m2, NOT_MASTERED);

        MasteryBranch branch = branch(topic);

        assertThat(branch.distribution()).isEqualTo(new Distribution(1, 0, 1, 2));
    }

    /** Сценарии «Раздел складывает поддерево» и «Метод считается сквозь Темы». */
    @Test
    void sectionAggregatesItsTopicsAndAMethodAggregatesAcrossThem() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topicA = library.topic(section);
        TaxonomyNodeId topicB = library.topic(section);
        SolutionMethodId method = library.method();
        library.problem(topicA, method);
        library.problem(topicB, method);
        marks.put(owner, student, topicA, method, MASTERED);
        marks.put(owner, student, topicB, method, NOT_MASTERED);

        MasteryOverview overview = service.overviewOf(student);

        assertThat(findBranch(overview.tree(), section).distribution())
                .as("Раздел — сумма его Тем").isEqualTo(new Distribution(1, 0, 1, 0));
        assertThat(overview.methods()).extracting(MasteryOfMethodRow::method)
                .extracting(m -> m.id()).contains(method);
        MasteryOfMethodRow row = overview.methods().stream()
                .filter(r -> r.method().id().equals(method)).findFirst().orElseThrow();
        assertThat(row.distribution()).as("Метод — сквозь обе Темы").isEqualTo(new Distribution(1, 0, 1, 0));
    }

    /** Сценарий «„Семь и один“ против „один и семь“ различаются» — сложение не огрубляет до одного числа. */
    @Test
    void distributionsWithSwappedCountsAreNotEqual() {
        TaxonomyNodeId topicMostlyMastered = library.topic();
        SolutionMethodId a1 = library.method();
        SolutionMethodId a2 = library.method();
        SolutionMethodId a3 = library.method();
        library.problem(List.of(topicMostlyMastered), List.of(a1, a2, a3), List.of(), ExamPart.SECOND);
        marks.put(owner, student, topicMostlyMastered, a1, MASTERED);
        marks.put(owner, student, topicMostlyMastered, a2, MASTERED);
        marks.put(owner, student, topicMostlyMastered, a3, NOT_MASTERED);

        TaxonomyNodeId topicMostlyNotMastered = library.topic();
        SolutionMethodId b1 = library.method();
        SolutionMethodId b2 = library.method();
        SolutionMethodId b3 = library.method();
        library.problem(List.of(topicMostlyNotMastered), List.of(b1, b2, b3), List.of(), ExamPart.SECOND);
        marks.put(owner, student, topicMostlyNotMastered, b1, MASTERED);
        marks.put(owner, student, topicMostlyNotMastered, b2, NOT_MASTERED);
        marks.put(owner, student, topicMostlyNotMastered, b3, NOT_MASTERED);

        Distribution mostlyMastered = branch(topicMostlyMastered).distribution();
        Distribution mostlyNotMastered = branch(topicMostlyNotMastered).distribution();

        assertThat(mostlyMastered).isEqualTo(new Distribution(2, 0, 1, 0));
        assertThat(mostlyNotMastered).isEqualTo(new Distribution(1, 0, 2, 0));
        assertThat(mostlyMastered).isNotEqualTo(mostlyNotMastered);
    }

    /** Сценарий «Метод в словаре, но не в разметке этой Темы, в её ячейки не входит». */
    @Test
    void unrelatedDictionaryMethodsDoNotLeakIntoTopicCells() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId used1 = library.method();
        SolutionMethodId used2 = library.method();
        library.problem(List.of(topic), List.of(used1, used2), List.of(), ExamPart.SECOND);
        for (int i = 0; i < 8; i++) {
            library.method();
        }

        assertThat(branch(topic).distribution()).isEqualTo(new Distribution(0, 0, 0, 2));
    }

    /** Сценарий «Узел без Задач в поддереве» — ячеек нет. */
    @Test
    void topicWithoutProblemsHasNoCells() {
        TaxonomyNodeId empty = library.topic();

        assertThat(branch(empty).distribution().cells()).isZero();
    }

    /** Сценарий «Метод без ячеек в таблице отсутствует». */
    @Test
    void methodWithoutCellsIsAbsentFromTheTable() {
        SolutionMethodId unused = library.method();

        MasteryOverview overview = service.overviewOf(student);

        assertThat(overview.methods()).extracting(row -> row.method().id()).doesNotContain(unused);
    }

    /** Сценарий «Пробелы — только „не владеет“, с путём и именем; без отметок — пусто». */
    @Test
    void gapsListOnlyNotMasteredCellsWithPathAndName() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId gapMethod = library.method();
        SolutionMethodId masteredMethod = library.method();
        SolutionMethodId uncertainMethod = library.method();
        library.problem(List.of(topic), List.of(gapMethod, masteredMethod, uncertainMethod), List.of(), ExamPart.SECOND);
        marks.put(owner, student, topic, gapMethod, NOT_MASTERED);
        marks.put(owner, student, topic, masteredMethod, MASTERED);
        marks.put(owner, student, topic, uncertainMethod, UNCERTAIN);

        MasteryOverview overview = service.overviewOf(student);

        assertThat(overview.gaps()).hasSize(1);
        Gap gap = overview.gaps().get(0);
        assertThat(gap.topic()).isEqualTo(topic);
        assertThat(gap.topicPath()).isNotBlank();
        assertThat(gap.method()).isEqualTo(gapMethod);
        assertThat(gap.methodName()).isNotBlank();
    }

    /** Пробелов у Ученика без единой отметки нет. */
    @Test
    void studentWithoutMarksHasNoGaps() {
        TaxonomyNodeId topic = library.topic();
        library.problem(topic, library.method());

        assertThat(service.overviewOf(student).gaps()).isEmpty();
    }

    /** Сценарий «Чужой Ученик» — неотличим от несуществующего. */
    @Test
    void foreignStudentIsNotFound() {
        UserId anotherTeacher = accounts.settled(Role.TEACHER).id();
        StudentId foreign = students.create(anotherTeacher, TestLibrary.unique("Чужой Ученик"));

        assertThatThrownBy(() -> service.overviewOf(foreign)).isInstanceOf(StudentNotFoundException.class);
    }

    /** Сценарий «Отметка другого Учителя на той же паре не учтена и в пробелах отсутствует» (ADR-0027). */
    @Test
    void anotherTeachersMarkOnTheSamePairIsNotCountedOrListedAsAGap() {
        TestAccounts.Account another = accounts.settled(Role.TEACHER);
        StudentId othersPupil = students.create(another.id(), TestLibrary.unique("Чужой Ученик"));
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        library.problem(topic, method);
        marks.put(another.id(), othersPupil, topic, method, NOT_MASTERED);

        MasteryOverview overview = service.overviewOf(student);

        assertThat(branch(topic).distribution())
                .as("отметка другого Учителя не входит в распределение").isEqualTo(new Distribution(0, 0, 0, 1));
        assertThat(overview.gaps()).as("и не становится пробелом").isEmpty();
    }

    private MasteryBranch branch(TaxonomyNodeId topic) {
        MasteryBranch found = findBranch(service.overviewOf(student).tree(), topic);
        if (found == null) {
            throw new AssertionError("Узел не найден в дереве владения: " + topic);
        }
        return found;
    }

    private static MasteryBranch findBranch(List<MasteryBranch> branches, TaxonomyNodeId id) {
        for (MasteryBranch branch : branches) {
            if (branch.node().id().equals(id)) {
                return branch;
            }
            MasteryBranch found = findBranch(branch.children(), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
