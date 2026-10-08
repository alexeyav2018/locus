package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задачи 4.2 и 4.3: стыки Расписания с Группами и выбытием — через настоящий
 * вход. Удаление Группы уносит её Занятия и предупреждает об этом заранее,
 * Ученики и их собственные Занятия остаются; Ученик, выбывший после
 * назначения, показывается в неделе с пометкой «выбыл».
 */
class LessonsAndStudentsScreenTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private LessonRepository lessons;

    /** Сценарий «Удаление Группы с Занятием». */
    @Test
    void deletingAGroupTakesItsLessonsAndKeepsStudentsWithTheirOwn() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        String groupName = TestLibrary.unique("9Б");
        String studentName = TestLibrary.unique("Иванов Пётр");
        GroupId group = groups.create(teacher.id(), groupName);
        StudentId student = students.create(teacher.id(), studentName);
        groups.setMembers(teacher.id(), group, List.of(student));
        lessons.create(teacher.id(), group, LessonTiming.weekly(TUESDAY, null, LocalTime.of(17, 0), 60));
        lessons.create(teacher.id(), student, LessonTiming.weekly(TUESDAY, null, LocalTime.of(19, 0), 60));
        Browser browser = loggedIn(teacher);

        assertThat(browser.get("/groups/" + group.value()).body())
                .contains("Занятия этой Группы будут удалены вместе с ней");
        assertThat(browser.get("/schedule?week=2026-10-13").body()).contains(groupName);

        Browser.Page deleted = browser.postForm("/groups/" + group.value() + "/deletion", Map.of());

        assertThat(deleted.status()).isIn(302, 303);
        String week = browser.get("/schedule?week=2026-10-13").body();
        assertThat(week).doesNotContain(groupName).doesNotContain("17:00–18:00");
        assertThat(week).as("собственное Занятие Ученика осталось").contains(studentName).contains("19:00–20:00");
        assertThat(students.findById(teacher.id(), student)).isPresent();
    }

    /** Сценарий «Ученик выбыл после назначения»: Встречи остаются, с пометкой. */
    @Test
    void studentWithdrawnAfterAssignmentIsMarkedInTheWeek() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        String name = TestLibrary.unique("Иванов Пётр");
        StudentId student = students.create(teacher.id(), name);
        lessons.create(teacher.id(), student, LessonTiming.weekly(TUESDAY, null, LocalTime.of(17, 0), 60));
        Browser browser = loggedIn(teacher);
        assertThat(browser.get("/schedule?week=2026-10-13").body()).contains(name).doesNotContain("выбыл");

        browser.postForm("/students/" + student.value() + "/withdrawal", Map.of());

        String week = browser.get("/schedule?week=2026-10-13").body();
        assertThat(week).contains(name).contains("17:00–18:00").contains(">выбыл<");
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
