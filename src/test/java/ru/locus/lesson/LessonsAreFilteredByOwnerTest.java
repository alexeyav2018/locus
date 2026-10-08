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

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
