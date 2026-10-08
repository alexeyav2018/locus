package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
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
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 3.2: страница Встречи и действия на ней (ADR-0048, ADR-0042).
 * Сегодня — вторник 27.10.2026; Занятие по вторникам в 17:00 с 06.10.2026.
 */
class MeetingScreenTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 11, 2);

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private TestClock clock;

    private TestAccounts.Account teacher;
    private String name;
    private String meeting;

    @BeforeEach
    void tuesdayWithAWeeklyLesson() {
        Instant noon = LocalDateTime.of(TODAY, LocalTime.NOON).atZone(clock.getZone()).toInstant();
        clock.shift(Duration.between(Instant.now(), noon));
        teacher = accounts.settled(Role.TEACHER);
        name = TestLibrary.unique("Петров");
        LessonId lesson = lessons.create(teacher.id(), students.create(teacher.id(), name),
                LessonTiming.weekly(LocalDate.of(2026, 10, 6), null, LocalTime.of(17, 0), 60));
        meeting = "/schedule/lessons/" + lesson.value() + "/meetings/" + TODAY;
    }

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    /** Проход «перенёс — увидел в другой неделе — вернул — отметил неявку — снял». */
    @Test
    void moveRestoreAndAbsenceRoundTrip() {
        Browser browser = loggedIn();
        assertThat(browser.get(meeting).body()).contains("Встреча: " + name).contains("как по расписанию");

        Browser.Page moved = browser.postForm(meeting + "/move?from=/schedule",
                Map.of("movedDate", NEXT_MONDAY.toString(), "start", "18:00", "durationMinutes", "60"));
        assertThat(moved.location()).as("возврат сохранён").contains(meeting + "?from=");
        assertThat(browser.get(meeting).body()).contains("Перенесена на 02.11.2026, 18:00–19:00");
        assertThat(browser.get("/schedule?week=" + NEXT_MONDAY).body())
                .contains(name).contains("перенесена с 27.10");
        assertThat(browser.get("/schedule?week=" + TODAY).body()).contains("перенесена на 02.11 18:00");

        assertThat(browser.postForm(meeting + "/restoration", Map.of()).redirectsTo(meeting)).isTrue();
        assertThat(browser.get("/schedule?week=" + NEXT_MONDAY).body())
                .as("в той неделе — только обычная Встреча 03.11").doesNotContain("перенесена с 27.10");

        browser.postForm(meeting + "/absence", Map.of("absent", "true"));
        assertThat(browser.get(meeting).body()).contains("не пришёл").contains("Ученик всё-таки пришёл");
        assertThat(browser.get("/schedule?week=" + TODAY).body()).contains("не пришёл");

        browser.postForm(meeting + "/absence", Map.of("absent", "false"));
        assertThat(browser.get(meeting).body()).doesNotContain("badge--warn").contains("как по расписанию");
    }

    /** Отказ — та же страница с сообщением и раскрытым блоком своего действия. */
    @Test
    void refusalOpensItsOwnBlock() {
        Browser browser = loggedIn();

        Browser.Page refused = browser.postForm(meeting + "/move",
                Map.of("movedDate", NEXT_MONDAY.toString(), "start", "18:00", "durationMinutes", "0"));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Длительность Встречи");
        assertThat(refused.body().indexOf("open=\"open\""))
                .as("раскрыт блок «Перенести»")
                .isPositive()
                .isLessThan(refused.body().indexOf("<summary>Перенести</summary>"));
        assertThat(refused.body()).containsOnlyOnce("open=\"open\"");
        assertThat(browser.get(meeting).body()).contains("как по расписанию");
    }

    /** Сценарий «Дата без Встречи»: среда у Занятия по вторникам — 404. */
    @Test
    void dateWithoutAMeetingIsNotFound() {
        assertThat(loggedIn().get(meeting.replace(TODAY.toString(), TODAY.plusDays(1).toString())).status())
                .isEqualTo(404);
    }

    private Browser loggedIn() {
        Browser browser = new Browser(port);
        browser.logIn(teacher.login(), teacher.password());
        return browser;
    }
}
