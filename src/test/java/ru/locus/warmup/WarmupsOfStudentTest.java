package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ru.locus.student.StudentInUseException;
import ru.locus.student.StudentRepository;
import ru.locus.student.StudentService;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 3.4: вопрос {@link ru.locus.student.StudentUsage} получает пятый
 * ответ — {@link WarmupsOfStudent}: участник Разминки не удаляется
 * (ADR-0035, ADR-0061).
 *
 * Считаются только Разминки вошедшего: чужой Ученик в Разминку вошедшего
 * не попадёт (составной ключ), но форму — владелец в запросе — тест держит.
 */
class WarmupsOfStudentTest extends IntegrationTest {

    private static final LocalDate HELD = LocalDate.of(2026, 10, 1);

    @Autowired
    private StudentService students;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WarmupRepository warmups;

    @Autowired
    private WarmupsOfStudent answer;

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

    /** Сценарий «Участник Разминки не удаляется»: в отказе названо число Разминок. */
    @Test
    void participantIsNotDeletedAndWarmupsAreNamed() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        warmups.create(owner, HELD, null, List.of(student), List.of(problem));

        assertThatThrownBy(() -> students.delete(student))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("участвовал в Разминках (1)");
        assertThat(students.student(student)).isNotNull();
    }

    @Test
    void onlyWarmupsOfTheLoggedInTeacherAreCounted() {
        StudentId mine = students.create(TestLibrary.unique("Иванов Пётр"));
        warmups.create(owner, HELD, null, List.of(mine), List.of(problem));
        UserId bob = accounts.settled(Role.TEACHER).id();
        StudentId theirs = studentRepository.create(bob, TestLibrary.unique("Сидорова Анна"));
        warmups.create(bob, HELD, null, List.of(theirs), List.of(problem));

        assertThat(answer.of(mine)).contains("участвовал в Разминках (1)");
        assertThat(answer.of(theirs)).as("чужой Ученик для вошедшего — без Разминок").isEmpty();
    }

    @Test
    void deletingTheWarmupReleasesTheStudent() {
        StudentId student = students.create(TestLibrary.unique("Иванов Пётр"));
        WarmupId warmup = warmups.create(owner, HELD, null, List.of(student), List.of(problem));

        warmups.delete(owner, warmup);

        assertThat(answer.of(student)).isEmpty();
    }
}
