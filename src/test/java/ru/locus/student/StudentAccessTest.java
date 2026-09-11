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
 * Требования «Ведут Учеников только Учителя» и «Учитель ведёт только свои
 * Группы»: и чтение, и правка — только роли Учителя.
 *
 * В отличие от библиотеки, где чтение открыто всем вошедшим, здесь
 * Администратор без роли Учителя не получает даже списка: у него нет
 * Учеников, и список для него не пуст, а недоступен — пустой список
 * притворялся бы, что владелец есть (design.md, «Права»).
 *
 * Отказ проверяется обращением по прямому адресу, а не отсутствием ссылки
 * на главной: доступность определяется правами, а не разметкой.
 * Обстановка готовится через репозиторий от имени Учителя: тесту нужно,
 * чтобы Ученик и Группа существовали, а не проверить права на их заведение.
 */
class StudentAccessTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    /** Сценарий «Администратор без роли Учителя»: ни списков, ни карточек. */
    @Test
    void administratorWithoutTeacherRoleIsRefusedEveryScreen() {
        TestAccounts.Account owner = accounts.settled(Role.TEACHER);
        StudentId student = students.create(owner.id(), "Иванов Пётр");
        GroupId group = groups.create(owner.id(), "9Б");
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        assertThat(administrator.get("/students").status()).as("список Учеников").isEqualTo(403);
        assertThat(administrator.get("/students/" + student.value()).status()).as("карточка Ученика").isEqualTo(403);
        assertThat(administrator.get("/groups").status()).as("список Групп").isEqualTo(403);
        assertThat(administrator.get("/groups/" + group.value()).status()).as("страница Группы").isEqualTo(403);
    }

    /** Сценарий «Администратор без роли Учителя»: ни одной операции. */
    @Test
    void administratorWithoutTeacherRoleIsRefusedEveryOperation() {
        TestAccounts.Account owner = accounts.settled(Role.TEACHER);
        StudentId student = students.create(owner.id(), "Иванов Пётр");
        GroupId group = groups.create(owner.id(), "9Б");
        Browser administrator = loggedIn(Role.ADMINISTRATOR);

        assertThat(administrator.postForm("/students", Map.of("name", "Чужой")).status())
                .as("заведение Ученика").isEqualTo(403);
        assertThat(administrator.postForm("/students/" + student.value() + "/name", Map.of("name", "Другой")).status())
                .as("переименование Ученика").isEqualTo(403);
        assertThat(administrator.postForm("/students/" + student.value() + "/deletion", Map.of()).status())
                .as("удаление Ученика").isEqualTo(403);
        assertThat(administrator.postForm("/groups", Map.of("name", "10А")).status())
                .as("заведение Группы").isEqualTo(403);
        assertThat(administrator.postForm("/groups/" + group.value() + "/name", Map.of("name", "10А")).status())
                .as("переименование Группы").isEqualTo(403);
        assertThat(administrator.postForm("/groups/" + group.value() + "/members",
                Map.of("studentIds", String.valueOf(student.value()))).status())
                .as("состав Группы").isEqualTo(403);
        assertThat(administrator.postForm("/groups/" + group.value() + "/deletion", Map.of()).status())
                .as("удаление Группы").isEqualTo(403);

        assertThat(students.findAll(owner.id())).extracting(Student::name).containsExactly("Иванов Пётр");
        assertThat(groups.findAll(owner.id())).extracting(Group::name).containsExactly("9Б");
        assertThat(groups.members(owner.id(), group)).as("состав не тронут").isEmpty();
    }

    /** Учитель, он же Администратор, к своим Ученикам допущен: роли складываются. */
    @Test
    void teacherIsAdmittedToStudentsAndGroups() {
        TestAccounts.Account owner = accounts.settled(Role.TEACHER, Role.ADMINISTRATOR);
        StudentId student = students.create(owner.id(), "Иванов Пётр");
        GroupId group = groups.create(owner.id(), "9Б");
        Browser teacher = new Browser(port);
        teacher.logIn(owner.login(), owner.password());

        assertThat(teacher.get("/students").status()).isEqualTo(200);
        assertThat(teacher.get("/students/" + student.value()).status()).isEqualTo(200);
        assertThat(teacher.get("/groups").status()).isEqualTo(200);
        assertThat(teacher.get("/groups/" + group.value()).status()).isEqualTo(200);
        assertThat(teacher.postForm("/groups/" + group.value() + "/members",
                List.of(Map.entry("studentIds", String.valueOf(student.value())))).status())
                .as("операция выполнена, ответ — переадресация")
                .isIn(302, 303);
        assertThat(groups.members(owner.id(), group)).containsExactly(student);
    }

    /** Сценарий «Обращение без входа». */
    @Test
    void withoutLoginEveryAddressLeadsToTheLoginForm() {
        TestAccounts.Account owner = accounts.settled(Role.TEACHER);
        StudentId student = students.create(owner.id(), "Иванов Пётр");
        GroupId group = groups.create(owner.id(), "9Б");
        Browser visitor = new Browser(port);

        for (String address : List.of("/students", "/students/" + student.value(),
                "/groups", "/groups/" + group.value())) {
            Browser.Page page = visitor.get(address);
            assertThat(page.redirectsTo("/login")).as("%s приводит к форме входа", address).isTrue();
            assertThat(page.body()).doesNotContain("Иванов Пётр").doesNotContain("9Б");
        }
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
