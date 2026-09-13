package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodRepository;
import ru.locus.problem.ProblemId;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Граница общего и личного для Заданий, обе половины разом и глазами
 * двух Учителей — по образцу {@link ru.locus.student.StudentsAreFilteredByOwnerTest}.
 *
 * Первая половина: Задания и Раздачи — личный контур. Чужие не видны
 * ни в сводке, ни по прямому адресу, ни через перенос срока и удаление,
 * а выдать Задание чужому Ученику или чужой Группе нельзя — они неотличимы
 * от несуществующих, и ни одной строки не появляется. Вторая половина:
 * два Учителя, выдавшие одну и ту же Задачу, видят на своих Заданиях одну
 * разметку и один материал — состав Задания ссылается на общую библиотеку,
 * и фильтр по владельцу на неё не просочился.
 *
 * Проверка идёт через настоящий вход, а не вызовом репозитория: обе ошибки
 * границы тихие (CLAUDE.md, «Доменный инвариант»). Забытый фильтр покажет
 * Учителю чужие Задания, лишний — спрячет от него библиотеку в составе
 * собственного Задания; и то, и другое видно, только сравнив то, что
 * видят разные люди.
 *
 * Обстановка Учителя А заводится через репозитории с его {@code UserId}:
 * тест сторожит экраны, а выдача через форму уже проверена
 * в {@link AssignmentScreenTest}. Исключение — обратная половина, где
 * выдача идёт формой у обоих: там важен путь от кнопки до страницы.
 */
class AssignmentsAreFilteredByOwnerTest extends IntegrationTest {

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

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private AssignmentBatchRepository batches;

    @Autowired
    private SolutionMethodRepository methods;

    /** Задание и Раздача Учителя А в сводке Учителя Б не показываются — ни строкой, ни в отборе. */
    @Test
    void anotherTeachersAssignmentsAndBatchesAreNotListed() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        String studentName = TestLibrary.unique("Иванов Пётр");
        String groupName = TestLibrary.unique("9Б");
        StudentId student = students.create(first.id(), studentName);
        AssignmentId personal = issued(first, student, null);
        AssignmentBatchId batch = batches.create(first.id(), groupName, today());
        AssignmentId ofBatch = issued(first, student, batch);
        Browser other = loggedIn(second);

        String summary = other.get("/assignments").body();
        String byStudent = other.get("/assignments?student=" + student.value()).body();
        String byBatch = other.get("/assignments?batch=" + batch.value()).body();

        assertThat(summary)
                .as("чужие Задания не показаны ни строкой, ни именем Ученика")
                .doesNotContain(row(personal)).doesNotContain(row(ofBatch))
                .doesNotContain(studentName)
                .as("чужой Ученик и чужая Раздача не предлагаются в отборе")
                .doesNotContain("value=\"" + student.value() + "\"")
                .doesNotContain(groupName);
        assertThat(byStudent).as("отбор по чужому Ученику пуст").doesNotContain(row(personal)).doesNotContain(row(ofBatch));
        assertThat(byBatch).as("отбор по чужой Раздаче пуст").doesNotContain(row(ofBatch)).doesNotContain(groupName);
        assertThat(loggedIn(first).get("/assignments").body())
                .as("своему владельцу оба Задания и Раздача по-прежнему видны")
                .contains(row(personal)).contains(row(ofBatch)).contains(studentName).contains(groupName);
    }

    /** Прямой адрес чужого Задания или Раздачи отвечает так же, как несуществующий. */
    @Test
    void anotherTeachersAssignmentAndBatchAreIndistinguishableFromMissing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        String studentName = TestLibrary.unique("Иванов Пётр");
        String groupName = TestLibrary.unique("9Б");
        StudentId student = students.create(first.id(), studentName);
        AssignmentBatchId batch = batches.create(first.id(), groupName, today());
        AssignmentId assignment = issued(first, student, batch);
        Browser other = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page foreignAssignment = other.get("/assignments/" + assignment.value());
        Browser.Page missingAssignment = other.get("/assignments/" + (assignment.value() + 1_000_000));
        Browser.Page foreignBatch = other.get("/assignments/batches/" + batch.value());
        Browser.Page missingBatch = other.get("/assignments/batches/" + (batch.value() + 1_000_000));

        assertThat(foreignAssignment.status()).as("чужое Задание — 404, а не 403 или 200").isEqualTo(404);
        assertThat(foreignAssignment.status()).isEqualTo(missingAssignment.status());
        assertThat(foreignAssignment.body()).doesNotContain(studentName);
        assertThat(foreignBatch.status()).as("чужая Раздача — 404").isEqualTo(404);
        assertThat(foreignBatch.status()).isEqualTo(missingBatch.status());
        assertThat(foreignBatch.body()).doesNotContain(groupName).doesNotContain(studentName);
    }

    /** Перенос срока и удаление чужого Задания и чужой Раздачи ничего не меняют. */
    @Test
    void movingDueDateAndDeletingAnotherTeachersAssignmentsChangeNothing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        StudentId student = students.create(first.id(), TestLibrary.unique("Иванов Пётр"));
        AssignmentId personal = issued(first, student, null);
        AssignmentBatchId batch = batches.create(first.id(), TestLibrary.unique("9Б"), today());
        AssignmentId ofBatch = issued(first, student, batch);
        LocalDate due = assignments.findById(first.id(), personal).orElseThrow().dueDate();
        Browser other = loggedIn(accounts.settled(Role.TEACHER));

        Browser.Page moved = other.postForm("/assignments/" + personal.value() + "/due-date",
                Map.of("dueDate", due.plusDays(30).toString()));
        Browser.Page deleted = other.postForm("/assignments/" + personal.value() + "/deletion", Map.of());
        Browser.Page batchDeleted = other.postForm("/assignments/batches/" + batch.value() + "/deletion", Map.of());

        assertThat(moved.status()).isEqualTo(404);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(batchDeleted.status()).isEqualTo(404);
        assertThat(assignments.findById(first.id(), personal))
                .as("Задание на месте и с прежним сроком")
                .get()
                .extracting(Assignment::dueDate)
                .isEqualTo(due);
        assertThat(batches.findById(first.id(), batch)).as("Раздача на месте").isPresent();
        assertThat(assignments.findByBatch(first.id(), batch))
                .as("Задание Раздачи на месте")
                .extracting(Assignment::id)
                .containsExactly(ofBatch);
    }

    /** Выдача чужому Ученику или чужой Группе — как несуществующим: ни Задания, ни Раздачи не появляется. */
    @Test
    void issuingToAnotherTeachersStudentOrGroupCreatesNothing() {
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        StudentId foreignStudent = students.create(first.id(), TestLibrary.unique("Иванов Пётр"));
        GroupId foreignGroup = groups.create(first.id(), TestLibrary.unique("9Б"));
        groups.setMembers(first.id(), foreignGroup, List.of(foreignStudent));
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        ProblemId problem = library.problem(library.topic());
        Browser other = loggedIn(second);

        Browser.Page toStudent = other.postForm("/assignments", issue(problem, "student", foreignStudent.value()));
        Browser.Page toMissingStudent = other.postForm("/assignments",
                issue(problem, "student", foreignStudent.value() + 1_000_000));
        Browser.Page toGroup = other.postForm("/assignments", issue(problem, "group", foreignGroup.value()));
        Browser.Page toMissingGroup = other.postForm("/assignments",
                issue(problem, "group", foreignGroup.value() + 1_000_000));

        assertThat(toStudent.status()).as("чужой Ученик — 404, а не 403 и не форма").isEqualTo(404);
        assertThat(toStudent.status()).isEqualTo(toMissingStudent.status());
        assertThat(toGroup.status()).as("чужая Группа — 404").isEqualTo(404);
        assertThat(toGroup.status()).isEqualTo(toMissingGroup.status());
        assertThat(assignments.find(second.id(), null, null, null, null)).as("у Б Заданий нет").isEmpty();
        assertThat(assignments.find(first.id(), null, null, null, null)).as("и у А их не появилось").isEmpty();
        assertThat(batches.findAll(second.id())).as("Раздачи у Б нет").isEmpty();
        assertThat(batches.findAll(first.id())).as("и у А нет").isEmpty();
        assertThat(assignments.countByProblem(problem)).as("Задача ни в одно Задание не вошла").isZero();
    }

    /**
     * Обратная половина: оба Учителя выдали одну Задачу своим Ученикам
     * и видят её на своих Заданиях с одной разметкой и одним материалом.
     * Задание — личное, но его состав ссылается на общую библиотеку,
     * и фильтр по владельцу Задания не должен дотянуться до неё.
     */
    @Test
    void teachersIssuingTheSameProblemSeeTheSameMarksAndTheory() {
        String methodName = TestLibrary.unique("Замена переменной");
        SolutionMethodId method = methods.create(methodName);
        TaxonomyNodeId topic = library.topic();
        ProblemId problem = library.problem(topic, method);
        String materialTitle = TestLibrary.unique("Тождества приведения");
        library.material(topic, materialTitle);
        TestAccounts.Account first = accounts.settled(Role.TEACHER);
        TestAccounts.Account second = accounts.settled(Role.TEACHER);
        StudentId ofFirst = students.create(first.id(), TestLibrary.unique("Иванов Пётр"));
        StudentId ofSecond = students.create(second.id(), TestLibrary.unique("Сидоров Олег"));
        Browser one = loggedIn(first);
        Browser another = loggedIn(second);

        String pageForOne = issuedPage(one, problem, ofFirst);
        String pageForAnother = issuedPage(another, problem, ofSecond);

        assertThat(pageForOne)
                .as("первый Учитель видит Задачу с её Методом и материалом Темы")
                .contains("№ " + problem.value())
                .contains("Методы: " + methodName)
                .contains(materialTitle);
        assertThat(pageForAnother)
                .as("и второй — ту же Задачу, тот же Метод и тот же материал")
                .contains("№ " + problem.value())
                .contains("Методы: " + methodName)
                .contains(materialTitle);
        assertThat(assignments.countByProblem(problem))
                .as("вопрос библиотеки считает Задания обоих Учителей")
                .isEqualTo(2);
    }

    private String issuedPage(Browser teacher, ProblemId problem, StudentId student) {
        Browser.Page done = teacher.postForm("/assignments", issue(problem, "student", student.value()));
        assertThat(done.status()).isIn(302, 303);
        String location = done.location();
        Browser.Page page = teacher.get(location.substring(location.indexOf("/assignments")));
        assertThat(page.status()).isEqualTo(200);
        return page.body();
    }

    private AssignmentId issued(TestAccounts.Account owner, StudentId student, AssignmentBatchId batch) {
        return assignments.create(owner.id(), student, batch, today(), today().plusDays(7), TheoryScope.TOPICS,
                List.of(library.problem(library.topic())));
    }

    private static List<Map.Entry<String, String>> issue(ProblemId problem, String addressee, long id) {
        return List.of(
                Map.entry("problem", String.valueOf(problem.value())),
                Map.entry("dueDate", today().plusDays(7).toString()),
                Map.entry("theoryScope", TheoryScope.TOPICS.name()),
                Map.entry(addressee, String.valueOf(id)));
    }

    private static LocalDate today() {
        return LocalDate.now();
    }

    private static String row(AssignmentId id) {
        return "id=\"assignment-" + id.value() + "\"";
    }

    private Browser loggedIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
