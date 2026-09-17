package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
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
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkId;
import ru.locus.work.StudentWorkRepository;
import ru.locus.work.Verdict;

/**
 * Задача 6.2: раздел «Владение» на экране приёма — ячейки у принятой
 * Работы, выборочная простановка одной формой, снятие через «неизвестно»,
 * справка «решено N из M», независимость от вердикта.
 *
 * Сравнивается видимый человеку текст, а не разметка (antipatterns.md);
 * исключение — {@code viewport}: экран под телефон, и раздел не должен
 * этого сломать. Работы заводятся через репозиторий с ключом-заглушкой:
 * экран файл не читает, только подписывает ссылку, а приём
 * с настоящими снимками проверен в {@code WorkScreenTest}.
 *
 * Форма шлёт три параллельных списка с повторяющимися именами — так
 * шлёт браузер, и {@code Map} этого не выразит, поэтому поля идут
 * списком пар.
 */
class MasteryScreenTest extends IntegrationTest {

    private static final String UNKNOWN_SHOWN = "сейчас: неизвестно ·";

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

    @Autowired
    private MasteryRepository marks;

    private UserId owner;
    private Browser teacher;
    private StudentId student;
    private TaxonomyNodeId topicA;
    private TaxonomyNodeId topicB;
    private SolutionMethodId methodX;
    private SolutionMethodId methodY;
    private ProblemId problem;
    private ProblemId other;
    private AssignmentId assignment;

    @BeforeEach
    void logInAndIssueAProblemOfTwoTopicsAndTwoMethods() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        owner = account.id();
        teacher = new Browser(port);
        teacher.logIn(account.login(), account.password());
        student = students.create(owner, TestLibrary.unique("Иванов Пётр"));
        topicA = library.topic();
        topicB = library.topic();
        methodX = library.method();
        methodY = library.method();
        problem = library.problem(List.of(topicA, topicB), List.of(methodX, methodY), List.of(), ExamPart.SECOND);
        other = library.problem(topicA, methodX);
        assignment = issue(problem, other);
    }

    /** Сценарий «До приёма Работы ячеек нет»: раздела на экране нет, экран по-прежнему под телефон. */
    @Test
    void withoutAWorkThereIsNoMasterySection() {
        String body = teacher.get(screen()).body();

        assertThat(body)
                .contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .doesNotContain("Владение")
                .doesNotContain("Сохранить отметки");
    }

    /** Сценарий «Ячейки у принятой Работы»: четыре ячейки «неизвестно», форма одна — у Задачи с Работой. */
    @Test
    void acceptedWorkShowsFourUnknownCellsAndOneForm() {
        receive(assignment, problem);

        String body = teacher.get(screen()).body();

        assertThat(body).contains("Владение").contains("без изменения");
        assertThat(count(body, UNKNOWN_SHOWN + " решено 0 из 0")).as("четыре ячейки «неизвестно»").isEqualTo(4);
        assertThat(count(body, "Сохранить отметки")).as("форма только у Задачи с Работой").isEqualTo(1);
        assertThat(count(body, "Принять Работу")).as("у второй Задачи — форма приёма").isEqualTo(1);
    }

    /** Сценарии «Выборочная простановка» и «Снятие отметки»: две меняются, две прежние; «неизвестно» снимает. */
    @Test
    void savingTwoCellsLeavesTheOtherTwoAndUnknownClears() {
        receive(assignment, problem);

        Browser.Page posted = teacher.postForm("/mastery", form(
                cell(topicA, methodX, "MASTERED"),
                cell(topicA, methodY, ""),
                cell(topicB, methodX, "NOT_MASTERED"),
                cell(topicB, methodY, "")));

        assertThat(posted.redirectsTo(screen())).as("после сохранения — назад на экран приёма").isTrue();
        String body = teacher.get(screen()).body();
        assertThat(count(body, "сейчас: владеет ·")).isEqualTo(1);
        assertThat(count(body, "сейчас: не владеет ·")).isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN)).as("две нетронутые").isEqualTo(2);
        assertThat(marks.countByStudent(owner, student)).isEqualTo(2);

        teacher.postForm("/mastery", form(cell(topicA, methodX, "UNKNOWN")));

        body = teacher.get(screen()).body();
        assertThat(count(body, "сейчас: владеет ·")).isZero();
        assertThat(count(body, UNKNOWN_SHOWN)).isEqualTo(3);
        assertThat(marks.countByStudent(owner, student)).as("строка снята, а не записана как UNKNOWN").isEqualTo(1);
    }

    /** Сценарий «Справка по проверенным Работам»: три проверенные и одна нет по другому Заданию — «решено 2 из 3». */
    @Test
    void hintCountsCheckedWorksOfAnotherAssignment() {
        receive(assignment, problem);
        ProblemId p1 = library.problem(topicA, methodX);
        ProblemId p2 = library.problem(topicA, methodX);
        ProblemId p3 = library.problem(topicA, methodX);
        ProblemId p4 = library.problem(topicA, methodX);
        AssignmentId another = issue(p1, p2, p3, p4);
        works.setVerdict(owner, receive(another, p1), Verdict.CORRECT, "");
        works.setVerdict(owner, receive(another, p2), Verdict.INCORRECT, "");
        works.setVerdict(owner, receive(another, p3), Verdict.CORRECT, "");
        receive(another, p4);

        String body = teacher.get(screen()).body();

        assertThat(count(body, UNKNOWN_SHOWN + " решено 2 из 3")).as("«A × X»").isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN + " решено 0 из 0")).as("остальные три").isEqualTo(3);
    }

    /** Сценарий «Вердикт не влияет на отметки»: «верно» меняет справку, но не ячейки (ADR-0014). */
    @Test
    void verdictChangesTheHintButNotTheCells() {
        StudentWorkId work = receive(assignment, problem);
        teacher.postForm("/mastery", form(cell(topicA, methodX, "MASTERED")));

        teacher.postForm("/works/" + work.value() + "/verdict", Map.of("verdict", "CORRECT", "note", ""));

        String body = teacher.get(screen()).body();
        assertThat(body).contains("Вердикт: верно");
        assertThat(count(body, "сейчас: владеет · решено 1 из 1")).isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN + " решено 1 из 1")).as("три ячейки так и не тронуты").isEqualTo(3);
        assertThat(marks.countByStudent(owner, student)).isEqualTo(1);
    }

    /** Отказ сервиса показывается на том же экране: пара не из разметки. */
    @Test
    void refusalIsShownOnTheSameScreen() {
        receive(assignment, problem);
        SolutionMethodId stranger = library.method();

        Browser.Page refused = teacher.postForm("/mastery", form(cell(topicA, stranger, "MASTERED")));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Ячейки нет").contains("Владение");
        assertThat(marks.countByStudent(owner, student)).isZero();
    }

    private AssignmentId issue(ProblemId... problems) {
        return assignments.create(owner, student, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8),
                TheoryScope.NONE, List.of(problems));
    }

    private StudentWorkId receive(AssignmentId assignment, ProblemId problem) {
        return works.create(owner, assignment, problem, LocalDate.of(2026, 9, 3), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
    }

    private String screen() {
        return "/works?assignment=" + assignment.value();
    }

    @SafeVarargs
    private final List<Map.Entry<String, String>> form(List<Map.Entry<String, String>>... cells) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("assignment", String.valueOf(assignment.value())));
        fields.add(Map.entry("problem", String.valueOf(problem.value())));
        for (List<Map.Entry<String, String>> cell : cells) {
            fields.addAll(cell);
        }
        return fields;
    }

    private static List<Map.Entry<String, String>> cell(TaxonomyNodeId topic, SolutionMethodId method, String status) {
        return List.of(
                Map.entry("topic", String.valueOf(topic.value())),
                Map.entry("method", String.valueOf(method.value())),
                Map.entry("status", status));
    }

    private static int count(String body, String text) {
        int count = 0;
        int from = 0;
        while ((from = body.indexOf(text, from)) >= 0) {
            count++;
            from += text.length();
        }
        return count;
    }
}
