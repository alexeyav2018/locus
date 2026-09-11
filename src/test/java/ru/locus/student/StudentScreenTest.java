package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Экраны Учеников: список с заведением и карточка с переименованием
 * и удалением — глазами вошедшего Учителя, по настоящему HTTP.
 *
 * Каждый тест входит своей учётной записью, поэтому список каждого
 * начинается пустым и содержит ровно то, что тест завёл: изоляция
 * по владельцу здесь не проверяется, а используется
 * ({@code StudentsAreFilteredByOwnerTest} проверяет её отдельно).
 *
 * Отсутствие скриптов в шаблонах проверяется на всех шаблонах разом
 * ({@code TaxonomyScreenTest.noTemplateCarriesAScript}), и новые шаблоны
 * попадают под ту же проверку.
 */
class StudentScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    /** Сценарий «Заведение Ученика»: список показывает своих по имени. */
    @Test
    void listShowsTheTeachersStudentsByName() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        StudentId petrov = students.create(teacher.id(), "Петров Иван");
        StudentId ivanov = students.create(teacher.id(), "Иванов Пётр");

        Browser.Page page = loggedIn(teacher).get("/students");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .as("показаны оба Ученика, разметка собрана на сервере")
                .contains("Иванов Пётр")
                .contains("Петров Иван")
                .doesNotContain("th:text");
        assertThat(page.body().indexOf(anchor(ivanov)))
                .as("порядок алфавитный, а не порядок заведения")
                .isLessThan(page.body().indexOf(anchor(petrov)));
        assertThat(page.body()).as("на списке есть форма заведения").contains("Завести Ученика");
    }

    /** Учитель без единого Ученика видит форму заведения, а не пустой экран. */
    @Test
    void emptyListStillOffersTheCreationForm() {
        String body = loggedIn(accounts.settled(Role.TEACHER)).get("/students").body();

        assertThat(body).contains("Учеников пока нет").contains("Завести Ученика");
    }

    /** Сценарий «Заведение Ученика»: через форму, с переходом на карточку. */
    @Test
    void creationThroughTheFormLeadsToTheCard() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page done = teacher.postForm("/students", Map.of("name", "Иванов Пётр"));

        assertThat(done.status()).isIn(302, 303);
        assertThat(done.location()).as("после заведения — на карточку").matches(".*/students/\\d+$");
        Browser.Page card = teacher.get(relative(done.location()));
        assertThat(card.status()).isEqualTo(200);
        assertThat(card.body()).contains("<h1>Иванов Пётр</h1>");
        assertThat(teacher.get("/students").body()).as("и в списке он есть").contains("Иванов Пётр");
    }

    /** Сценарий «Два Ученика с одним именем». */
    @Test
    void twoStudentsMayShareAName() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page first = teacher.postForm("/students", Map.of("name", "Иванов Пётр"));
        Browser.Page second = teacher.postForm("/students", Map.of("name", "Иванов Пётр"));

        assertThat(second.status()).as("второй заводится").isIn(302, 303);
        assertThat(second.location()).isNotEqualTo(first.location());
        assertThat(count(teacher.get("/students").body(), "Иванов Пётр"))
                .as("в списке показаны оба")
                .isEqualTo(2);
    }

    /** Сценарий «Пустое имя». */
    @Test
    void emptyNameIsRefusedWithAMessage() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page page = teacher.postForm("/students", Map.of("name", "   "));

        assertThat(page.status()).as("та же страница, не переадресация").isEqualTo(200);
        assertThat(page.body()).contains("не может быть пустым");
        assertThat(teacher.get("/students").body()).as("Ученик не заведён").contains("Учеников пока нет");
    }

    /** Карточка: имя, Группы, формы переименования и удаления. */
    @Test
    void cardShowsNameGroupsAndActions() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId student = students.create(account.id(), "Иванов Пётр");
        GroupId group = groups.create(account.id(), "9Б");
        groups.setMembers(account.id(), group, java.util.List.of(student));

        String body = loggedIn(account).get("/students/" + student.value()).body();

        assertThat(body).contains("<h1>Иванов Пётр</h1>");
        assertThat(body).as("Группы Ученика показаны ссылками").contains("9Б").contains("/groups/" + group.value());
        assertThat(body)
                .contains("/students/" + student.value() + "/name")
                .contains("Переименовать")
                .contains("/students/" + student.value() + "/deletion")
                .contains("Удалить Ученика");
    }

    /** Сценарий «Учитель переименовывает Ученика». */
    @Test
    void renamingThroughTheFormComesBackToTheCardUnderTheNewName() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId student = students.create(account.id(), "Иванов Пётр");
        Browser teacher = loggedIn(account);

        Browser.Page done = teacher.postForm("/students/" + student.value() + "/name", Map.of("name", "Иванов Павел"));

        assertThat(done.redirectsTo("/students/" + student.value())).isTrue();
        assertThat(teacher.get("/students/" + student.value()).body()).contains("<h1>Иванов Павел</h1>");
    }

    /** Переименование в пустое имя: карточка с сообщением, имя прежнее. */
    @Test
    void renamingToAnEmptyNameShowsTheCardWithAMessage() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId student = students.create(account.id(), "Иванов Пётр");

        Browser.Page page = loggedIn(account).postForm("/students/" + student.value() + "/name", Map.of("name", ""));

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("не может быть пустым").contains("<h1>Иванов Пётр</h1>");
    }

    /** Сценарий «Удаление Ученика, на которого ничего не ссылается». */
    @Test
    void deletionLeadsToTheListWithoutTheStudent() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId student = students.create(account.id(), "Иванов Пётр");
        GroupId group = groups.create(account.id(), "9Б");
        groups.setMembers(account.id(), group, java.util.List.of(student));
        Browser teacher = loggedIn(account);

        Browser.Page done = teacher.postForm("/students/" + student.value() + "/deletion", Map.of());

        assertThat(done.redirectsTo("/students")).isTrue();
        assertThat(teacher.get("/students").body()).doesNotContain("Иванов Пётр");
        assertThat(teacher.get("/students/" + student.value()).status()).isEqualTo(404);
        assertThat(teacher.get("/groups/" + group.value()).body())
                .as("Группа осталась, Ученика в ней нет")
                .contains("<h1>9Б</h1>")
                .doesNotContain("Иванов Пётр");
    }

    /** С главной страницы Учитель переходит к Ученикам ссылкой. */
    @Test
    void studentsAreReachableFromTheHomePage() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        assertThat(teacher.get("/").body())
                .as("в навигации есть пункт Учеников")
                .contains("href=\"/students\"");
        assertThat(teacher.get("/students").status()).isEqualTo(200);
    }

    private static int count(String body, String fragment) {
        int found = 0;
        for (int at = body.indexOf(fragment); at >= 0; at = body.indexOf(fragment, at + fragment.length())) {
            found++;
        }
        return found;
    }

    private static String anchor(StudentId id) {
        return "id=\"student-" + id.value() + "\"";
    }

    private static String relative(String location) {
        return location.substring(location.indexOf("/students"));
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
