package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
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
 * Задача 2.5: граница личного контура глазами двух Учителей, как
 * в {@code StudentsAreFilteredByOwnerTest}. Занятия — личные: Встреча
 * Учителя А не видна Учителю Б ни в неделе, ни на главной.
 *
 * <p>Проверка идёт через настоящий вход: забытый фильтр по владельцу
 * не падает, а тихо показывает чужое расписание, и заметить это можно,
 * только сравнив то, что видят разные люди.
 */
class LessonsAreFilteredByOwnerTest extends IntegrationTest {

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
    private MeetingAdjustmentRepository adjustments;

    /** Сценарий «Неделя другого Учителя» — и та же Встреча на главной. */
    @Test
    void anotherTeachersMeetingsAreShownNeitherInTheWeekNorOnTheHomePage() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        String studentName = TestLibrary.unique("Иванов Пётр");
        String groupName = TestLibrary.unique("9Б");
        StudentId student = students.create(first.id(), studentName);
        GroupId group = groups.create(first.id(), groupName);
        LocalDate today = LocalDate.now();
        LessonId weekly = lessons.create(first.id(), student,
                LessonTiming.weekly(today.minusWeeks(1), null, LocalTime.of(17, 0), 60));
        LessonId once = lessons.create(first.id(), group, LessonTiming.once(today, LocalTime.of(12, 0), 60));
        Browser own = loggedIn(first);
        Browser other = loggedIn(second);

        assertThat(own.get("/schedule").body())
                .as("своему владельцу обе Встречи видны в неделе")
                .contains(studentName)
                .contains(groupName);
        assertThat(own.get("/").body())
                .as("и на главной")
                .contains(studentName)
                .contains(groupName);
        assertThat(other.get("/schedule").body())
                .as("чужие Встречи не показаны в неделе ни именем, ни ссылкой")
                .doesNotContain(studentName)
                .doesNotContain(groupName)
                .doesNotContain("/schedule/lessons/" + weekly.value() + "?")
                .doesNotContain("/schedule/lessons/" + once.value() + "?");
        assertThat(other.get("/").body())
                .as("и на главной")
                .doesNotContain(studentName)
                .doesNotContain(groupName)
                .contains("Сегодня Встреч нет");
    }

    /**
     * Сценарий «Отмена Встречи чужого Занятия» (ADR-0048): Учитель Б не
     * открывает и не поправляет Встречу Учителя А — ни отменой, ни переносом,
     * ни неявкой, — а перенос, поставленный А, не виден Б в неделе, куда
     * Встреча перенесена.
     */
    @Test
    void anotherTeachersMeetingIsNeitherOpenedNorAdjusted() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        String studentName = TestLibrary.unique("Сидоров Илья");
        LocalDate tuesday = LocalDate.of(2026, 10, 20);
        LocalDate thursday = LocalDate.of(2026, 10, 22);
        LessonId weekly = lessons.create(first.id(), students.create(first.id(), studentName),
                LessonTiming.weekly(LocalDate.of(2026, 10, 6), null, LocalTime.of(17, 0), 60));
        adjustments.save(first.id(), MeetingAdjustment.none(weekly, LocalDate.of(2026, 10, 13))
                .withMove(new MeetingAdjustment.Move(thursday, LocalTime.of(18, 0), 60)));
        String meeting = "/schedule/lessons/" + weekly.value() + "/meetings/" + tuesday;
        Browser own = loggedIn(first);
        Browser other = loggedIn(second);

        assertThat(other.get(meeting).status()).as("чужая Встреча — как несуществующая").isEqualTo(404);
        assertThat(other.postForm(meeting + "/cancellation", Map.of()).status()).isEqualTo(404);
        assertThat(other.postForm(meeting + "/move",
                Map.of("movedDate", thursday.toString(), "start", "10:00", "durationMinutes", "60")).status())
                .isEqualTo(404);
        assertThat(other.postForm(meeting + "/absence", Map.of("absent", "true")).status()).isEqualTo(404);
        assertThat(other.postForm(meeting + "/restoration", Map.of()).status()).isEqualTo(404);

        assertThat(adjustments.find(first.id(), weekly, tuesday)).as("у А Встреча как прежде").isEmpty();
        assertThat(own.get(meeting).body()).contains("как по расписанию");
        assertThat(own.get("/schedule?week=" + thursday).body())
                .as("свой перенос владелец видит")
                .contains("перенесена с 13.10");
        assertThat(other.get("/schedule?week=" + thursday).body())
                .as("Поправки А не видны Б")
                .doesNotContain(studentName)
                .doesNotContain("перенесена");
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
