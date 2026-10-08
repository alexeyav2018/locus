package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestClock;
import ru.locus.TestLibrary;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 2.4: главная Учителя показывает Встречи сегодняшнего дня
 * по часам приложения ({@link TestClock}) в порядке времени, а пустой
 * день — словами и ссылкой на расписание.
 */
class TodaysMeetingsOnHomeTest extends IntegrationTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);

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

    @Autowired
    private TestClock clock;

    @BeforeEach
    void thursday() {
        Instant noon = LocalDateTime.of(THURSDAY, LocalTime.NOON).atZone(clock.getZone()).toInstant();
        clock.shift(Duration.between(Instant.now(), noon));
    }

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    /** Сценарий «Сегодня есть Встречи»: 15:00 и 17:00 — в этом порядке, хоть заведены наоборот. */
    @Test
    void todaysMeetingsAreShownInOrderOfTime() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        String studentName = TestLibrary.unique("Иванов Пётр");
        String groupName = TestLibrary.unique("9Б");
        StudentId student = students.create(teacher.id(), studentName);
        GroupId group = groups.create(teacher.id(), groupName);
        lessons.create(teacher.id(), student,
                LessonTiming.weekly(THURSDAY.minusWeeks(2), null, LocalTime.of(17, 0), 60));
        lessons.create(teacher.id(), group, LessonTiming.once(THURSDAY, LocalTime.of(15, 0), 90));
        lessons.create(teacher.id(), group, LessonTiming.once(THURSDAY.plusDays(1), LocalTime.of(9, 0), 60));

        String home = loggedIn(teacher).get("/").body();

        assertThat(home).contains("15:00–16:30").contains("17:00–18:00").doesNotContain("09:00–10:00");
        assertThat(home.indexOf(groupName)).as("Встреча в 15:00 идёт раньше Встречи в 17:00")
                .isPositive()
                .isLessThan(home.indexOf(studentName));
        assertThat(home).doesNotContain("Сегодня Встреч нет");
    }

    /** Сценарий «Сегодня Встреч нет». */
    @Test
    void emptyDayIsSaidInWordsWithLinkToTheSchedule() {
        String home = loggedIn(accounts.settled(Role.TEACHER)).get("/").body();

        assertThat(home).contains("Сегодня Встреч нет").contains("href=\"/schedule\"");
    }

    /** У Администратора без роли Учителя карточки дня нет и главная открывается. */
    @Test
    void administratorHomeHasNoMeetings() {
        Browser.Page home = loggedIn(accounts.settled(Role.ADMINISTRATOR)).get("/");

        assertThat(home.status()).isEqualTo(200);
        assertThat(home.body()).doesNotContain("Сегодня Встреч нет");
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
