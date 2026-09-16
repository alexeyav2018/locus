package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileKey;
import ru.locus.file.FileType;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Граница общего и личного для Работ, обе половины разом и глазами двух
 * Учителей — по образцу {@link ru.locus.assignment.AssignmentsAreFilteredByOwnerTest}.
 *
 * Первая половина: Работы — личный контур. Чужие не видны ни экраном
 * приёма, ни списком Работ Ученика; вердикт, файлы и удаление чужой
 * Работы не выполняются, а принять Работу по чужому Заданию нельзя —
 * ответ такой же, как для несуществующего, и ни одной строки не появляется.
 * Вторая половина: два Учителя, принявшие Работы по одной и той же Задаче
 * библиотеки, видят каждый только свою — Задача общая, Работы личные,
 * и фильтр по владельцу лёг ровно на Работы.
 *
 * Проверка идёт через настоящий вход: обе ошибки границы тихие
 * (CLAUDE.md, «Доменный инвариант»), и видны они, только сравнив то,
 * что видят разные люди. Обстановка Учителя А — через репозитории с его
 * {@code UserId}; приём формой уже проверен в {@link WorkScreenTest}.
 */
class WorksAreFilteredByOwnerTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private StudentRepository students;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    private TestAccounts.Account alice;
    private TestAccounts.Account bob;
    private Browser bobsBrowser;
    private ProblemId problem;
    private StudentId alicesStudent;
    private AssignmentId alicesAssignment;
    private StudentWorkId alicesWork;

    @BeforeEach
    void setTheScene() {
        alice = accounts.settled(Role.TEACHER);
        bob = accounts.settled(Role.TEACHER);
        bobsBrowser = loggedIn(bob);
        problem = library.problem(library.topic());
        alicesStudent = students.create(alice.id(), TestLibrary.unique("Иванов Пётр"));
        LocalDate today = LocalDate.now();
        alicesAssignment = assignments.create(alice.id(), alicesStudent, null, today, today.plusDays(7),
                TheoryScope.NONE, List.of(problem, library.problem(library.topic())));
        alicesWork = works.create(alice.id(), alicesAssignment, problem, today, null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg"), new FileKey(UUID.randomUUID() + ".jpg")));
    }

    /** Сценарий «Экран приёма чужого Задания»: и экран приёма, и список Работ Ученика. */
    @Test
    void anotherTeachersScreensAreNotFound() {
        assertThat(bobsBrowser.get("/works?assignment=" + alicesAssignment.value()).status())
                .as("экран приёма чужого Задания").isEqualTo(404);
        assertThat(bobsBrowser.get("/works?student=" + alicesStudent.value()).status())
                .as("Работы чужого Ученика").isEqualTo(404);
    }

    /** Сценарий «Приём по чужому Заданию»: 404 и Работ не появилось. */
    @Test
    void receivingForAnotherTeachersAssignmentIsNotFoundAndLeavesNothing() {
        List<ProblemId> composition = assignments.findById(alice.id(), alicesAssignment).orElseThrow().problems();
        ProblemId free = composition.get(1);

        Browser.Page posted = bobsBrowser.postMultipart(
                "/works?assignment=" + alicesAssignment.value() + "&problem=" + free.value(),
                Map.of(), List.of(new Browser.FilePart("files", "photo.jpg", FileType.JPEG, WorkFilesTest.resource("rotated.jpg"))));

        assertThat(posted.status()).isEqualTo(404);
        assertThat(works.findByAssignment(alice.id(), alicesAssignment)).as("у А по-прежнему одна Работа").hasSize(1);
        assertThat(works.findByAssignment(bob.id(), alicesAssignment)).isEmpty();
    }

    /** Сценарий «Правка чужой Работы»: вердикт, файлы, удаление — 404, Работа А прежняя. */
    @Test
    void editingAnotherTeachersWorkIsNotFoundAndChangesNothing() {
        String prefix = "/works/" + alicesWork.value();
        StudentWorkFileId file = works.findById(alice.id(), alicesWork).orElseThrow().files().get(0).id();

        assertThat(bobsBrowser.postForm(prefix + "/verdict", Map.of("verdict", "CORRECT", "note", "чужое")).status())
                .as("вердикт").isEqualTo(404);
        assertThat(bobsBrowser.postMultipart(prefix + "/files", Map.of(), Map.of("files", TestLibrary.pdf())).status())
                .as("добавление файлов").isEqualTo(404);
        assertThat(bobsBrowser.postForm(prefix + "/files/" + file.value() + "/deletion", Map.of()).status())
                .as("удаление файла").isEqualTo(404);
        assertThat(bobsBrowser.postForm(prefix + "/deletion", Map.of()).status())
                .as("удаление Работы").isEqualTo(404);

        StudentWork left = works.findById(alice.id(), alicesWork).orElseThrow();
        assertThat(left.isChecked()).as("вердикта нет").isFalse();
        assertThat(left.note()).isEmpty();
        assertThat(left.files()).as("оба файла на месте").hasSize(2);
    }

    /** Обратная половина: одна Задача библиотеки, две Работы у двух Учителей, каждый видит свою. */
    @Test
    void twoTeachersReceiveWorksOnTheSameProblemAndEachSeesOnlyTheirOwn() {
        StudentId bobsStudent = students.create(bob.id(), TestLibrary.unique("Сидорова Анна"));
        LocalDate today = LocalDate.now();
        AssignmentId bobsAssignment = assignments.create(bob.id(), bobsStudent, null, today, today.plusDays(7),
                TheoryScope.NONE, List.of(problem));
        Browser.Page posted = bobsBrowser.postMultipart(
                "/works?assignment=" + bobsAssignment.value() + "&problem=" + problem.value(),
                Map.of("verdict", "CORRECT", "note", "чисто"),
                List.of(new Browser.FilePart("files", "photo.jpg", FileType.JPEG, WorkFilesTest.resource("rotated.jpg"))));
        assertThat(posted.redirectsTo("/works?assignment=" + bobsAssignment.value())).isTrue();

        String bobsScreen = bobsBrowser.get("/works?assignment=" + bobsAssignment.value()).body();
        assertThat(bobsScreen).contains("№ " + problem.value()).contains("Вердикт: верно").doesNotContain("не проверена");
        String alicesScreen = loggedIn(alice).get("/works?assignment=" + alicesAssignment.value()).body();
        assertThat(alicesScreen).contains("№ " + problem.value()).contains("не проверена").doesNotContain("Вердикт: верно");

        assertThat(works.findByStudent(alice.id(), alicesStudent)).hasSize(1);
        assertThat(works.findByStudent(bob.id(), bobsStudent)).hasSize(1);
        assertThat(works.findByStudent(bob.id(), alicesStudent)).as("Б не видит Работ Ученика А").isEmpty();
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
