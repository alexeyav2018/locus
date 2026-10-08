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
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 2.3: неделя и главная показывают Поправки Встреч (ADR-0048) —
 * «отменена», «перенесена с …», строку «перенесена на …» на плановом дне
 * и «не пришёл», — а Встреча ведёт на свою страницу по плановой дате.
 */
class MeetingMarksOnScreenTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private MeetingAdjustmentRepository adjustments;

    @Autowired
    private TestClock clock;

    private TestAccounts.Account teacher;
    private String cancelledName;
    private String movedName;
    private String absentName;
    private LessonId cancelled;
    private LessonId moved;

    @BeforeEach
    void thursdayWithThreeAdjustedMeetings() {
        Instant noon = LocalDateTime.of(THURSDAY, LocalTime.NOON).atZone(clock.getZone()).toInstant();
        clock.shift(Duration.between(Instant.now(), noon));
        teacher = accounts.settled(Role.TEACHER);
        cancelledName = TestLibrary.unique("Отменённый");
        movedName = TestLibrary.unique("Перенесённый");
        absentName = TestLibrary.unique("Непришедший");

        cancelled = lessons.create(teacher.id(), students.create(teacher.id(), cancelledName),
                LessonTiming.weekly(TUESDAY, null, LocalTime.of(15, 0), 60));
        adjustments.save(teacher.id(), MeetingAdjustment.none(cancelled, TUESDAY).withCancelled(true));

        moved = lessons.create(teacher.id(), students.create(teacher.id(), movedName),
                LessonTiming.weekly(TUESDAY, null, LocalTime.of(17, 0), 60));
        adjustments.save(teacher.id(), MeetingAdjustment.none(moved, TUESDAY)
                .withMove(new MeetingAdjustment.Move(THURSDAY, LocalTime.of(18, 0), 60)));

        LessonId absent = lessons.create(teacher.id(), students.create(teacher.id(), absentName),
                LessonTiming.once(THURSDAY, LocalTime.of(10, 0), 60));
        adjustments.save(teacher.id(), MeetingAdjustment.none(absent, THURSDAY).withAbsent(true));
    }

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    /** Неделя: отмена и строка «перенесена на» во вторник, перенесённая Встреча и неявка в четверг. */
    @Test
    void weekShowsCancellationMoveAndAbsence() {
        String week = loggedIn().get("/schedule").body();
        String tuesday = section(week, "day-" + TUESDAY);
        String thursday = section(week, "day-" + THURSDAY);

        assertThat(tuesday).contains(cancelledName).contains("отменена")
                .contains(movedName).contains("перенесена на 08.10 18:00")
                .doesNotContain("не пришёл");
        assertThat(thursday).contains(movedName).contains("18:00–19:00").contains("перенесена с 06.10")
                .contains(absentName).contains("не пришёл")
                .doesNotContain("отменена");
        assertThat(week).contains("/schedule/lessons/" + cancelled.value() + "/meetings/" + TUESDAY)
                .contains("/schedule/lessons/" + moved.value() + "/meetings/" + TUESDAY);
    }

    /** Главная: сегодняшние Встречи с пометками — перенесённая сюда и неявка. */
    @Test
    void homeShowsTodaysMarks() {
        String home = loggedIn().get("/").body();

        assertThat(home).contains(movedName).contains("перенесена с 06.10")
                .contains(absentName).contains("не пришёл")
                .doesNotContain(cancelledName)
                .contains("/schedule/lessons/" + moved.value() + "/meetings/" + TUESDAY);
    }

    /** Кусок страницы от открытия секции дня до её закрытия. */
    private static String section(String page, String id) {
        int start = page.indexOf("id=\"" + id + "\"");
        assertThat(start).as("день %s на странице", id).isPositive();
        return page.substring(start, page.indexOf("</section>", start));
    }

    private Browser loggedIn() {
        Browser browser = new Browser(port);
        browser.logIn(teacher.login(), teacher.password());
        return browser;
    }
}
