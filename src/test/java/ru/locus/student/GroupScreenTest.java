package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Экраны Групп: список с заведением и страница Группы с переименованием,
 * составом галочками и удалением — глазами вошедшего Учителя,
 * по настоящему HTTP.
 *
 * Каждый тест входит своей учётной записью — см. {@link StudentScreenTest}.
 */
class GroupScreenTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    /** Сценарий «Заведение Группы»: список показывает свои по имени. */
    @Test
    void listShowsTheTeachersGroupsByName() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        GroupId second = groups.create(teacher.id(), "9Б");
        GroupId first = groups.create(teacher.id(), "9А");

        Browser.Page page = loggedIn(teacher).get("/groups");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("9А").contains("9Б").doesNotContain("th:text");
        assertThat(page.body().indexOf(anchor(first)))
                .as("порядок алфавитный")
                .isLessThan(page.body().indexOf(anchor(second)));
        assertThat(page.body()).contains("Завести Группу");
    }

    /** Сценарий «Заведение Группы»: через форму, с переходом на страницу Группы. */
    @Test
    void creationThroughTheFormLeadsToTheGroupPage() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page done = teacher.postForm("/groups", Map.of("name", "9Б"));

        assertThat(done.status()).isIn(302, 303);
        assertThat(done.location()).matches(".*/groups/\\d+$");
        Browser.Page page = teacher.get(relative(done.location()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<h1>9Б</h1>");
        assertThat(teacher.get("/groups").body()).contains("9Б");
    }

    /** Сценарий «Имя Группы занято» — в другом регистре тоже. */
    @Test
    void takenNameInAnotherCaseIsRefusedWithAMessage() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        groups.create(account.id(), "9Б");
        Browser teacher = loggedIn(account);

        Browser.Page page = teacher.postForm("/groups", Map.of("name", "9б"));

        assertThat(page.status()).as("та же страница, не переадресация").isEqualTo(200);
        assertThat(page.body()).contains("уже есть");
        assertThat(groups.findAll(account.id())).as("вторая Группа не заведена").hasSize(1);
    }

    /** Сценарий «Пустое имя» у Группы. */
    @Test
    void emptyNameIsRefusedWithAMessage() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page page = teacher.postForm("/groups", Map.of("name", " "));

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("не может быть пустым").contains("Групп пока нет");
    }

    /** Сценарий «Состав Группы»: галочки по всем Ученикам, сохраняется целиком. */
    @Test
    void membershipIsSetByCheckboxesAndShownBack() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId ivanov = students.create(account.id(), "Иванов Пётр");
        StudentId petrov = students.create(account.id(), "Петров Иван");
        StudentId sidorov = students.create(account.id(), "Сидоров Олег");
        GroupId group = groups.create(account.id(), "9Б");
        Browser teacher = loggedIn(account);

        String before = teacher.get("/groups/" + group.value()).body();
        assertThat(before)
                .as("галочки по всем Ученикам владельца, ни одна не отмечена")
                .contains(checkbox(ivanov))
                .contains(checkbox(petrov))
                .contains(checkbox(sidorov))
                .doesNotContain("checked");

        Browser.Page done = teacher.postForm("/groups/" + group.value() + "/members", List.of(
                Map.entry("studentIds", String.valueOf(ivanov.value())),
                Map.entry("studentIds", String.valueOf(sidorov.value()))));

        assertThat(done.redirectsTo("/groups/" + group.value())).isTrue();
        String after = teacher.get("/groups/" + group.value()).body();
        assertThat(count(after, "checked=\"checked\"")).as("отмечены ровно выбранные").isEqualTo(2);
        assertThat(after).contains(checkedCheckbox(ivanov)).contains(checkedCheckbox(sidorov));
        assertThat(groups.members(account.id(), group)).containsExactlyInAnyOrder(ivanov, sidorov);
        assertThat(teacher.get("/students/" + ivanov.value()).body())
                .as("на карточке Ученика видна его Группа")
                .contains("9Б");
    }

    /** Сценарий «Состав Группы»: форма без единой галочки опустошает Группу. */
    @Test
    void submittingNoCheckboxesEmptiesTheGroup() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId ivanov = students.create(account.id(), "Иванов Пётр");
        GroupId group = groups.create(account.id(), "9Б");
        groups.setMembers(account.id(), group, List.of(ivanov));
        Browser teacher = loggedIn(account);

        Browser.Page done = teacher.postForm("/groups/" + group.value() + "/members", Map.of());

        assertThat(done.redirectsTo("/groups/" + group.value())).isTrue();
        assertThat(groups.members(account.id(), group)).isEmpty();
        assertThat(teacher.get("/groups/" + group.value()).body()).doesNotContain("checked");
    }

    /** Переименование Группы через форму. */
    @Test
    void renamingThroughTheFormComesBackUnderTheNewName() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        GroupId group = groups.create(account.id(), "9Б");
        groups.create(account.id(), "10А");
        Browser teacher = loggedIn(account);

        Browser.Page done = teacher.postForm("/groups/" + group.value() + "/name", Map.of("name", "9В"));
        Browser.Page taken = teacher.postForm("/groups/" + group.value() + "/name", Map.of("name", "10а"));

        assertThat(done.redirectsTo("/groups/" + group.value())).isTrue();
        assertThat(taken.status()).as("занятое имя — страница с сообщением").isEqualTo(200);
        assertThat(taken.body()).contains("уже есть").contains("<h1>9В</h1>");
    }

    /** Сценарий «Удаление Группы с составом»: Ученики остаются. */
    @Test
    void deletionLeadsToTheListAndKeepsTheStudents() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        StudentId ivanov = students.create(account.id(), "Иванов Пётр");
        GroupId group = groups.create(account.id(), "9Б");
        groups.setMembers(account.id(), group, List.of(ivanov));
        Browser teacher = loggedIn(account);

        Browser.Page done = teacher.postForm("/groups/" + group.value() + "/deletion", Map.of());

        assertThat(done.redirectsTo("/groups")).isTrue();
        assertThat(teacher.get("/groups").body()).doesNotContain("9Б");
        assertThat(teacher.get("/groups/" + group.value()).status()).isEqualTo(404);
        assertThat(teacher.get("/students/" + ivanov.value()).body())
                .as("карточка Ученика на месте, Группы у него больше нет")
                .contains("<h1>Иванов Пётр</h1>")
                .contains("Ни в одной Группе не состоит");
    }

    /** С главной страницы Учитель переходит к Группам ссылкой. */
    @Test
    void groupsAreReachableFromTheHomePage() {
        Browser teacher = loggedIn(accounts.settled(Role.TEACHER));

        assertThat(teacher.get("/").body()).contains("href=\"/groups\"");
        assertThat(teacher.get("/groups").status()).isEqualTo(200);
    }

    private static int count(String body, String fragment) {
        int found = 0;
        for (int at = body.indexOf(fragment); at >= 0; at = body.indexOf(fragment, at + fragment.length())) {
            found++;
        }
        return found;
    }

    private static String anchor(GroupId id) {
        return "id=\"group-" + id.value() + "\"";
    }

    private static String checkbox(StudentId id) {
        return "name=\"studentIds\" value=\"" + id.value() + "\"";
    }

    private static String checkedCheckbox(StudentId id) {
        return checkbox(id) + " checked=\"checked\"";
    }

    private static String relative(String location) {
        return location.substring(location.indexOf("/groups"));
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
