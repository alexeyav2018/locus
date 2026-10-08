package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 2.3: экран недели через настоящий вход — Встреча видна в своей
 * неделе и не видна в соседней, пустой день подписан, раздел есть в шапке,
 * а Пользователь без роли Учителя получает отказ.
 */
class ScheduleScreenTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private LessonRepository lessons;

    @Test
    void meetingIsShownInItsWeekAndNotInTheNeighbouringOnes() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        String name = TestLibrary.unique("Иванов Пётр");
        StudentId student = students.create(teacher.id(), name);
        LessonId lesson = lessons.create(teacher.id(), student, LessonTiming.once(TUESDAY, LocalTime.of(17, 0), 60));
        Browser browser = loggedIn(teacher);

        String week = browser.get("/schedule?week=2026-10-08").body();
        String previous = browser.get("/schedule?week=2026-09-29").body();
        String next = browser.get("/schedule?week=2026-10-13").body();

        assertThat(week)
                .contains("05.10.2026 — 11.10.2026")
                .contains("Вторник, 06.10")
                .contains(name)
                .contains("17:00–18:00")
                .contains("/schedule/lessons/" + lesson.value())
                .as("пустой день подписан")
                .contains("Встреч нет")
                .as("переходы на соседние недели")
                .contains("/schedule?week=2026-09-28")
                .contains("/schedule?week=2026-10-12");
        assertThat(previous).doesNotContain(name);
        assertThat(next).doesNotContain(name);
    }

    @Test
    void scheduleIsInTheTeachersNavigation() {
        String page = loggedIn(accounts.settled(Role.TEACHER)).get("/schedule").body();

        assertThat(page).contains("href=\"/schedule\"").contains("сегодня");
    }

    /** Сценарий «Администратор без роли Учителя». */
    @Test
    void administratorWithoutTeacherRoleIsRefused() {
        Browser administrator = loggedIn(accounts.settled(Role.ADMINISTRATOR));

        assertThat(administrator.get("/schedule").status()).isEqualTo(403);
        assertThat(administrator.get("/").body()).doesNotContain("href=\"/schedule\"");
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
