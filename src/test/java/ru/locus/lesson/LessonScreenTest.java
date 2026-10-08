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
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Задача 3.3: ведение Занятия через настоящий вход — заведение с формы,
 * Встреча в неделе, перевод с даты с делением, удаление; отказ формы
 * возвращает её с сообщением; чужое Занятие — 404 и не меняется.
 */
class LessonScreenTest extends IntegrationTest {

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
    void createSeeMoveAndDelete() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        String name = TestLibrary.unique("Иванов Пётр");
        StudentId student = students.create(teacher.id(), name);
        Browser browser = loggedIn(teacher);

        String form = browser.get("/schedule/lessons/new?date=2026-10-06").body();
        assertThat(form).contains("value=\"2026-10-06\"").contains(name);

        Browser.Page created = browser.postForm("/schedule/lessons", Map.of(
                "student", String.valueOf(student.value()),
                "group", "",
                "firstDate", "2026-10-06",
                "weekly", "true",
                "start", "17:00",
                "durationMinutes", "60"));
        assertThat(created.redirectsTo("/schedule?week=2026-10-06")).isTrue();
        String week = browser.get("/schedule?week=2026-10-06").body();
        assertThat(week).contains(name).contains("17:00–18:00");
        LessonId tuesdays = lessons.findCandidates(teacher.id(), TUESDAY, TUESDAY).getFirst().lesson().id();

        String card = browser.get("/schedule/lessons/" + tuesdays.value()).body();
        assertThat(card).contains("Каждую неделю: Вторник, 17:00–18:00").contains("бессрочно");

        Browser.Page moved = browser.postForm("/schedule/lessons/" + tuesdays.value(), Map.of(
                "dayOfWeek", "WEDNESDAY",
                "start", "19:00",
                "durationMinutes", "60",
                "lastDate", "",
                "effectiveFrom", "2026-10-19"));
        assertThat(moved.status()).isIn(302, 303);
        assertThat(moved.location()).matches(".*/schedule/lessons/\\d+$").doesNotEndWith("/" + tuesdays.value());
        assertThat(browser.get("/schedule?week=2026-10-13").body()).contains("17:00–18:00");
        String afterMove = browser.get("/schedule?week=2026-10-20").body();
        assertThat(afterMove).contains("19:00–20:00").doesNotContain("17:00–18:00");

        Browser.Page deleted = browser.postForm("/schedule/lessons/" + tuesdays.value() + "/deletion", Map.of());
        assertThat(deleted.redirectsTo("/schedule?week=2026-10-06")).isTrue();
        assertThat(browser.get("/schedule?week=2026-10-06").body()).doesNotContain(name);
        assertThat(browser.get("/schedule?week=2026-10-20").body()).as("продолжение осталось").contains(name);
    }

    /** Отказ формы: сообщение, введённое сохранено, ничего не заведено. */
    @Test
    void refusedFormIsShownAgain() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        StudentId student = students.create(teacher.id(), TestLibrary.unique("Иванов Пётр"));
        Browser browser = loggedIn(teacher);

        Browser.Page refused = browser.postForm("/schedule/lessons", Map.of(
                "student", "",
                "group", "",
                "firstDate", "2026-10-06",
                "start", "17:00",
                "durationMinutes", "60"));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("ровно один").contains("value=\"2026-10-06\"");
        assertThat(lessons.countByStudent(teacher.id(), student)).isZero();
    }

    /** Отказ правки раскрывает блок правки и не меняет Занятие. */
    @Test
    void refusedChangeOpensItsBlock() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        StudentId student = students.create(teacher.id(), TestLibrary.unique("Иванов Пётр"));
        LessonId lesson = lessons.create(teacher.id(), student,
                LessonTiming.weekly(TUESDAY, null, LocalTime.of(17, 0), 60));
        Browser browser = loggedIn(teacher);

        Browser.Page refused = browser.postForm("/schedule/lessons/" + lesson.value(), Map.of(
                "dayOfWeek", "WEDNESDAY",
                "start", "19:00",
                "durationMinutes", "60",
                "effectiveFrom", ""));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Укажите дату").contains("open=\"open\"");
        assertThat(lessons.findById(teacher.id(), lesson)).get()
                .extracting(found -> found.lesson().timing().start())
                .isEqualTo(LocalTime.of(17, 0));
    }

    /**
     * Задача 4.2, сценарий «Удаление Занятия с перенесённой Встречей»: формы
     * предупреждают о Поправках, а удаление убирает и Встречу, перенесённую
     * в другую неделю.
     */
    @Test
    void deletionTakesAMeetingMovedToAnotherWeek() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        String name = TestLibrary.unique("Иванов Пётр");
        StudentId student = students.create(teacher.id(), name);
        LessonId lesson = lessons.create(teacher.id(), student,
                LessonTiming.weekly(TUESDAY, LocalDate.of(2026, 10, 13), LocalTime.of(17, 0), 60));
        Browser browser = loggedIn(teacher);

        String card = browser.get("/schedule/lessons/" + lesson.value()).body();
        assertThat(card).contains("будут сняты").contains("отметками неявки");

        String thursday = "2026-10-22";
        assertThat(browser.postForm("/schedule/lessons/" + lesson.value() + "/meetings/2026-10-13/move",
                Map.of("movedDate", thursday, "start", "10:00", "durationMinutes", "60")).status())
                .isIn(302, 303);
        assertThat(browser.get("/schedule?week=" + thursday).body()).contains(name).contains("10:00–11:00");

        browser.postForm("/schedule/lessons/" + lesson.value() + "/deletion", Map.of());

        assertThat(browser.get("/schedule?week=" + thursday).body()).doesNotContain(name);
    }

    /** Сценарий «Правка чужого Занятия»: 404 на карточку, правку и удаление, Занятие прежнее. */
    @Test
    void foreignLessonIsNotFoundAndStaysTheSame() {
        TestAccounts.Account alice = accounts.settled(Role.TEACHER);
        StudentId student = students.create(alice.id(), TestLibrary.unique("Иванов Пётр"));
        LessonTiming timing = LessonTiming.weekly(TUESDAY, null, LocalTime.of(17, 0), 60);
        LessonId lesson = lessons.create(alice.id(), student, timing);
        Browser bob = loggedIn(accounts.settled(Role.TEACHER));

        assertThat(bob.get("/schedule/lessons/" + lesson.value()).status()).isEqualTo(404);
        assertThat(bob.postForm("/schedule/lessons/" + lesson.value(), Map.of(
                "dayOfWeek", "WEDNESDAY",
                "start", "19:00",
                "durationMinutes", "60",
                "effectiveFrom", "2026-10-19")).status()).isEqualTo(404);
        assertThat(bob.postForm("/schedule/lessons/" + lesson.value() + "/deletion", Map.of()).status())
                .isEqualTo(404);
        assertThat(lessons.findById(alice.id(), lesson)).get()
                .extracting(found -> found.lesson().timing())
                .isEqualTo(timing);
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
