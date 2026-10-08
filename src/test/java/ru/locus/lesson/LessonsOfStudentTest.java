package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentInUseException;
import ru.locus.student.StudentRepository;
import ru.locus.student.StudentService;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.1: вопрос {@link ru.locus.student.StudentUsage} получает четвёртый
 * ответ — {@link LessonsOfStudent}: Ученик, которому назначено Занятие,
 * не удаляется (ADR-0035, ADR-0047).
 *
 * <p>Отказ называет число Занятий вместе с прочими причинами. Занятие
 * Группы на члена не ссылается — Ученик из Группы с Занятием удаляется.
 * Считаются только Занятия вошедшего.
 */
class LessonsOfStudentTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    @Autowired
    private StudentService students;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private LessonsOfStudent answer;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;

    @BeforeEach
    void logInAsATeacher() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Ученик с Занятием»: отказ называет число вместе с прочими причинами. */
    @Test
    void studentWithLessonsIsNotDeletedAndAllReasonsAreNamed() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        lessons.create(owner, student, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        lessons.create(owner, student, LessonTiming.once(TUESDAY.plusDays(2), FIVE_PM, 90));
        assignments.create(owner, student, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8),
                TheoryScope.NONE, List.of(library.problem(library.topic())));

        assertThatThrownBy(() -> students.delete(student))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("выдано Заданий (1)")
                .hasMessageContaining("назначено Занятий (2)");
        assertThat(students.student(student)).isNotNull();
    }

    @Test
    void lessonOfAGroupDoesNotHoldItsMember() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        GroupId group = groups.create(owner, TestLibrary.unique("9Б"));
        groups.setMembers(owner, group, List.of(student));
        lessons.create(owner, group, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));

        assertThat(answer.of(student)).isEmpty();
        assertThatCode(() -> students.delete(student)).doesNotThrowAnyException();
    }

    @Test
    void onlyLessonsOfTheLoggedInTeacherAreCounted() {
        StudentId mine = students.create(TestLibrary.unique("Иванов Пётр"));
        lessons.create(owner, mine, LessonTiming.once(TUESDAY, FIVE_PM, 60));
        UserId bob = accounts.settled(Role.TEACHER).id();
        StudentId theirs = studentRepository.create(bob, TestLibrary.unique("Сидорова Анна"));
        lessons.create(bob, theirs, LessonTiming.once(TUESDAY, FIVE_PM, 60));

        assertThat(answer.of(mine)).contains("назначено Занятий (1)");
        assertThat(answer.of(theirs)).as("чужой Ученик для вошедшего — без Занятий").isEmpty();
    }
}
