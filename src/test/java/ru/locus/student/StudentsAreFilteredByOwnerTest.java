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
import ru.locus.TestLibrary;
import ru.locus.problem.ProblemId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Граница общего и личного, обе половины разом и глазами двух Учителей.
 *
 * Первая половина: Ученики и Группы — личный контур, и чужие не видны
 * ни в списке, ни по прямому адресу, ни через операции. Вторая половина,
 * перенесённая сюда из {@code auth-roles}: заведя себе по Ученику, те же
 * два Учителя по-прежнему видят одну и ту же библиотеку целиком — фильтр
 * по владельцу на личных данных не должен просочиться в общие.
 *
 * Проверка идёт через настоящий вход, а не вызовом репозитория, как
 * и в {@link ru.locus.problem.ProblemsAreNotFilteredTest}: обе ошибки
 * границы тихие. Забытый фильтр на личных данных не падает, а показывает
 * учителю чужих учеников; лишний фильтр на библиотеке не падает, а прячет
 * её. Заметить и то, и другое можно, только сравнив то, что видят
 * разные люди.
 */
class StudentsAreFilteredByOwnerTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    /** Ученик и Группа Учителя А в списках Учителя Б не показываются. */
    @Test
    void anotherTeachersStudentsAndGroupsAreNotListed() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        StudentId student = students.create(first.id(), "Иванов Пётр");
        GroupId group = groups.create(first.id(), "9Б");

        Browser other = loggedIn(second);

        assertThat(other.get("/students").body())
                .as("чужой Ученик не показан ни именем, ни ссылкой")
                .doesNotContain("Иванов Пётр")
                .doesNotContain("/students/" + student.value() + "\"");
        assertThat(other.get("/groups").body())
                .as("чужая Группа не показана ни именем, ни ссылкой")
                .doesNotContain("9Б")
                .doesNotContain("/groups/" + group.value() + "\"");
        assertThat(loggedIn(first).get("/students").body())
                .as("своему владельцу Ученик по-прежнему виден")
                .contains("Иванов Пётр");
    }

    /** Прямой адрес чужой карточки или Группы отвечает так же, как несуществующий. */
    @Test
    void anotherTeachersStudentAndGroupAreIndistinguishableFromMissing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        StudentId student = students.create(first.id(), "Иванов Пётр");
        GroupId group = groups.create(first.id(), "9Б");
        Browser other = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page foreignStudent = other.get("/students/" + student.value());
        Browser.Page missingStudent = other.get("/students/" + (student.value() + 1_000_000));
        Browser.Page foreignGroup = other.get("/groups/" + group.value());
        Browser.Page missingGroup = other.get("/groups/" + (group.value() + 1_000_000));

        assertThat(foreignStudent.status()).as("чужой Ученик — 404, а не 403 или 200").isEqualTo(404);
        assertThat(foreignStudent.status()).isEqualTo(missingStudent.status());
        assertThat(foreignStudent.body()).doesNotContain("Иванов Пётр");
        assertThat(foreignGroup.status()).as("чужая Группа — 404").isEqualTo(404);
        assertThat(foreignGroup.status()).isEqualTo(missingGroup.status());
        assertThat(foreignGroup.body()).doesNotContain("9Б");
    }

    /** Переименование и удаление чужого Ученика ничего не меняют. */
    @Test
    void renamingAndDeletingAnotherTeachersStudentChangeNothing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        StudentId student = students.create(first.id(), "Иванов Пётр");
        Browser other = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page renamed = other.postForm("/students/" + student.value() + "/name", Map.of("name", "Чужое имя"));
        Browser.Page deleted = other.postForm("/students/" + student.value() + "/deletion", Map.of());

        assertThat(renamed.status()).isEqualTo(404);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(students.findById(first.id(), student))
                .as("Ученик на месте и под прежним именем")
                .get()
                .extracting(Student::name)
                .isEqualTo("Иванов Пётр");
    }

    /** Переименование, удаление и состав чужой Группы ничего не меняют. */
    @Test
    void renamingDeletingAndRecomposingAnotherTeachersGroupChangeNothing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        StudentId member = students.create(first.id(), "Иванов Пётр");
        GroupId group = groups.create(first.id(), "9Б");
        groups.setMembers(first.id(), group, List.of(member));
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        StudentId own = students.create(second.id(), "Сидоров Олег");
        Browser other = loggedIn(second);

        Browser.Page renamed = other.postForm("/groups/" + group.value() + "/name", Map.of("name", "Чужое имя"));
        Browser.Page recomposed = other.postForm("/groups/" + group.value() + "/members",
                Map.of("studentIds", String.valueOf(own.value())));
        Browser.Page emptied = other.postForm("/groups/" + group.value() + "/members", Map.of());
        Browser.Page deleted = other.postForm("/groups/" + group.value() + "/deletion", Map.of());

        assertThat(renamed.status()).isEqualTo(404);
        assertThat(recomposed.status()).isEqualTo(404);
        assertThat(emptied.status()).isEqualTo(404);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(groups.findById(first.id(), group))
                .as("Группа на месте и под прежним именем")
                .get()
                .extracting(Group::name)
                .isEqualTo("9Б");
        assertThat(groups.members(first.id(), group))
                .as("состав не тронут")
                .containsExactly(member);
    }

    /** Чужого Ученика нельзя включить в свою Группу — состав не меняется. */
    @Test
    void anotherTeachersStudentCannotBeAddedToOnesOwnGroup() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        StudentId foreign = students.create(first.id(), "Иванов Пётр");
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        StudentId own = students.create(second.id(), "Сидоров Олег");
        GroupId group = groups.create(second.id(), "9Б");
        groups.setMembers(second.id(), group, List.of(own));
        Browser other = loggedIn(second);

        Browser.Page done = other.postForm("/groups/" + group.value() + "/members", List.of(
                Map.entry("studentIds", String.valueOf(own.value())),
                Map.entry("studentIds", String.valueOf(foreign.value()))));

        assertThat(done.status()).as("чужой Ученик неотличим от несуществующего").isEqualTo(404);
        assertThat(groups.members(second.id(), group))
                .as("состав остался прежним: ни чужого не добавилось, ни своего не пропало")
                .containsExactly(own);
        assertThat(groups.groupsOf(first.id(), foreign))
                .as("чужой Ученик ни в какую Группу не попал")
                .isEmpty();
        assertThat(other.get("/groups/" + group.value()).body())
                .as("галочки чужого Ученика на странице Группы нет")
                .doesNotContain("value=\"" + foreign.value() + "\"");
    }

    /**
     * Обратная половина: у обоих Учителей есть свои Ученики, а библиотека
     * для них всё равно одна — и на дереве, и в поиске. Появление личного
     * контура не должно было задеть общий.
     */
    @Test
    void teachersWithTheirOwnStudentsStillSeeTheSameLibrary() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        students.create(first.id(), "Иванов Пётр");
        students.create(second.id(), "Сидоров Олег");
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic);
        library.material(topic, "Тождества приведения");
        Browser one = loggedIn(first);
        Browser another = loggedIn(second);

        String treeForOne = one.get("/taxonomy?node=" + topic.value()).body();
        String treeForAnother = another.get("/taxonomy?node=" + topic.value()).body();
        String searchForOne = one.get("/problems?node=" + topic.value()).body();
        String searchForAnother = another.get("/problems?node=" + topic.value()).body();

        assertThat(treeForOne)
                .as("Задача и материал видны первому Учителю на дереве")
                .contains("№ " + problem.value())
                .contains("Тождества приведения");
        assertThat(treeForAnother)
                .as("и второму — та же Задача и тот же материал")
                .contains("№ " + problem.value())
                .contains("Тождества приведения");
        assertThat(searchForOne).as("Задача находится поиском у первого").contains("№ " + problem.value());
        assertThat(searchForAnother).as("и у второго").contains("№ " + problem.value());
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
