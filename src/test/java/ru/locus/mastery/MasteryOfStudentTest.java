package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.UNCERTAIN;
import static ru.locus.mastery.MasteryStatus.UNKNOWN;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentInUseException;
import ru.locus.student.StudentRepository;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkRepository;

/**
 * Задача 4.2: вопрос {@link ru.locus.student.StudentUsage} получает третий,
 * последний ответ — {@link MasteryOfStudent}: Ученик, о котором вынесено
 * суждение, не удаляется (ADR-0035).
 *
 * Отказ называет все причины разом: с Заданием, Работой и отметкой — три.
 * Считаются суждения, а не ячейки: снятая отметка ({@code UNKNOWN})
 * строки не оставляет (ADR-0039), и Ученик снова удаляется. Считаются
 * только отметки вошедшего — чужие для него неотличимы от несуществующих.
 */
class MasteryOfStudentTest extends IntegrationTest {

    @Autowired
    private StudentService students;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private MasteryOfStudent answer;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;
    private TaxonomyNodeId topic;
    private SolutionMethodId method;
    private ProblemId problem;

    @BeforeEach
    void logInAsATeacher() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
        topic = library.topic();
        method = library.method();
        problem = library.problem(topic, method);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Ученик с отметкой Владения». */
    @Test
    void studentWithAMarkIsNotDeleted() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        marks.put(owner, student, topic, method, MASTERED);

        assertThatThrownBy(() -> students.delete(student))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("суждений (1)");
        assertThat(students.student(student)).isNotNull();
    }

    /** Все три причины разом, каждая с числом. */
    @Test
    void allThreeReasonsAreNamedTogether() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        AssignmentId assignment = issue(student);
        receive(assignment);
        marks.put(owner, student, topic, method, MASTERED);
        marks.put(owner, student, topic, library.method(), UNCERTAIN);

        assertThatThrownBy(() -> students.delete(student))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("выдано Заданий (1)")
                .hasMessageContaining("принято Работ (1)")
                .hasMessageContaining("вынесено суждений (2)");
    }

    /** Снятая отметка строки не оставляет — Ученик освобождается. */
    @Test
    void studentIsDeletedOnceMarksAreWithdrawnAndAssignmentsRemoved() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        AssignmentId assignment = issue(student);
        marks.put(owner, student, topic, method, MASTERED);

        marks.put(owner, student, topic, method, UNKNOWN);
        assignments.delete(owner, assignment);

        assertThatCode(() -> students.delete(student)).doesNotThrowAnyException();
        assertThat(studentRepository.findById(owner, student)).as("Ученик удалён").isEmpty();
    }

    @Test
    void onlyMarksOfTheLoggedInTeacherAreCounted() {
        StudentId mine = students.create(TestLibrary.unique("Иванов Пётр"));
        marks.put(owner, mine, topic, method, MASTERED);
        UserId bob = accounts.settled(Role.TEACHER).id();
        StudentId theirs = studentRepository.create(bob, TestLibrary.unique("Сидорова Анна"));
        marks.put(bob, theirs, topic, method, MASTERED);

        assertThat(answer.of(mine)).contains("вынесено суждений (1)");
        assertThat(answer.of(theirs)).as("чужой Ученик для вошедшего — без суждений").isEmpty();
    }

    @Test
    void studentWithoutMarksGetsNoAnswerFromMastery() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        issue(student);

        assertThat(answer.of(student)).isEmpty();
    }

    private AssignmentId issue(StudentId student) {
        return assignments.create(owner, student, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8),
                TheoryScope.NONE, List.of(problem));
    }

    private void receive(AssignmentId assignment) {
        works.create(owner, assignment, problem, LocalDate.of(2026, 9, 3), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }
}
