package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ru.locus.file.FileKey;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentInUseException;
import ru.locus.student.StudentRepository;
import ru.locus.student.StudentService;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.1: вопрос {@link ru.locus.student.StudentUsage} получает второй
 * ответ — {@link WorksOfStudent}: Ученик, от которого приняты Работы,
 * не удаляется (ADR-0035).
 *
 * Ученик с Работой всегда имеет и Задание, поэтому отказ называет обе
 * причины — {@code StudentService} собирает ответы всех ответчиков.
 * Считаются только Работы вошедшего: у чужого Учителя с Заданием тому же
 * Ученику быть не может (составной ключ), но форму — владелец в запросе —
 * тест держит.
 */
class WorksOfStudentTest extends IntegrationTest {

    @Autowired
    private StudentService students;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private WorksOfStudent answer;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;
    private ProblemId problem;

    @BeforeEach
    void logInAsATeacher() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
        problem = library.problem(library.topic());
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Ученик с Работой»: названы и Задания, и Работы, с числом. */
    @Test
    void studentWithWorksIsNotDeletedAndBothReasonsAreNamed() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        AssignmentId assignment = issue(owner, student);
        receive(owner, assignment);

        assertThatThrownBy(() -> students.delete(student))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("выдано Заданий (1)")
                .hasMessageContaining("принято Работ (1)");
        assertThat(students.student(student)).isNotNull();
    }

    @Test
    void onlyWorksOfTheLoggedInTeacherAreCounted() {
        StudentId mine = students.create(TestLibrary.unique("Иванов Пётр"));
        receive(owner, issue(owner, mine));
        UserId bob = accounts.settled(Role.TEACHER).id();
        StudentId theirs = studentRepository.create(bob, TestLibrary.unique("Сидорова Анна"));
        receive(bob, issue(bob, theirs));

        assertThat(answer.of(mine)).contains("принято Работ (1)");
        assertThat(answer.of(theirs)).as("чужой Ученик для вошедшего — без Работ").isEmpty();
    }

    @Test
    void studentWithoutWorksGetsNoAnswerFromWorks() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        issue(owner, student);

        assertThat(answer.of(student)).isEmpty();
    }

    private AssignmentId issue(UserId teacher, StudentId student) {
        return assignments.create(teacher, student, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8),
                TheoryScope.NONE, List.of(problem));
    }

    private void receive(UserId teacher, AssignmentId assignment) {
        works.create(teacher, assignment, problem, LocalDate.of(2026, 9, 3), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }
}
