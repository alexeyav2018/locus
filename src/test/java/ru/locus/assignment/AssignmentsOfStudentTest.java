package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.student.StudentUsage;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.2: ответ «ссылается ли что-нибудь на Ученика» считает Задания
 * только вошедшего Учителя — владелец берётся у {@code CurrentUser},
 * как и положено вопросу личной области к личной же (ADR-0036, ADR-0035).
 *
 * Ответчик берётся из контекста как реализация {@link StudentUsage},
 * а не по классу: так проверяется и то, что {@code StudentService}
 * получит его в свой список.
 */
class AssignmentsOfStudentTest extends IntegrationTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 1);
    private static final LocalDate DUE = ISSUED.plusDays(7);

    @Autowired
    private List<StudentUsage> usages;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    @Test
    void assignmentsAnswerTheStudents() {
        assertThat(usages)
                .as("область Заданий отвечает на вопрос об Ученике")
                .anySatisfy(usage -> assertThat(usage).isInstanceOf(AssignmentsOfStudent.class));
    }

    @Test
    void onlyAssignmentsOfTheLoggedInTeacherAreCounted() {
        TestAccounts.Account alice = accounts.settled(Role.TEACHER);
        TestAccounts.Account bob = accounts.settled(Role.TEACHER);
        ProblemId problem = library.problem(library.topic());
        StudentId hers = students.create(alice.id(), unique("Иванова Мария"));
        StudentId his = students.create(bob.id(), unique("Сидоров Андрей"));
        issue(alice.id(), hers, problem);
        issue(alice.id(), hers, problem);
        issue(bob.id(), his, problem);

        LoggedIn.as(alice);
        assertThat(answer().of(hers)).contains("выдано Заданий (2)");
        assertThat(answer().of(his)).as("чужой Ученик для вошедшего — без ссылок").isEmpty();

        LoggedIn.as(bob);
        assertThat(answer().of(his)).contains("выдано Заданий (1)");
        assertThat(answer().of(hers)).as("чужой Ученик для вошедшего — без ссылок").isEmpty();
    }

    @Test
    void deletingTheLastAssignmentReleasesTheStudent() {
        TestAccounts.Account alice = accounts.settled(Role.TEACHER);
        StudentId student = students.create(alice.id(), unique("Иванова Мария"));
        AssignmentId only = issue(alice.id(), student, library.problem(library.topic()));
        LoggedIn.as(alice);

        assertThat(answer().of(student)).isPresent();

        assignments.delete(alice.id(), only);

        assertThat(answer().of(student)).as("Ученик без Заданий снова удаляется").isEmpty();
    }

    private StudentUsage answer() {
        return usages.stream()
                .filter(AssignmentsOfStudent.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private AssignmentId issue(UserId owner, StudentId student, ProblemId problem) {
        return assignments.create(owner, student, null, ISSUED, DUE, TheoryScope.NONE, List.of(problem));
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
