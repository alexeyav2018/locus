package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import ru.locus.file.FileType;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.warmup.WarmupId;
import ru.locus.warmup.WarmupOutcome;
import ru.locus.warmup.WarmupRepository;
import ru.locus.work.StudentWorkId;
import ru.locus.work.StudentWorkRepository;
import ru.locus.work.Verdict;

/**
 * Ячейки Владения на экране приёма (`work-mastery-at-receipt`): ячейки
 * в форме приёма у каждой Задачи, приём с отметкой одним действием, отказ
 * приёма без файла с выбранным на месте, один блок «Вердикт и владение»
 * у принятой Работы — выборочная простановка и снятие через «неизвестно»
 * его формой, справка «Работы: N из M · разминки: K из L», вердикт не трогает ячеек, отказ
 * по паре не из разметки — на том же экране при прежнем вердикте.
 *
 * Сравнивается видимый человеку текст, а не разметка (antipatterns.md);
 * исключения — {@code viewport} (экран под телефон) и отметки
 * {@code checked}/{@code selected} у отклонённой формы: выбранное в ней
 * только в разметке и видно. Работы, кроме приёма формой, заводятся через
 * репозиторий с ключом-заглушкой: экран файл не читает, только подписывает
 * ссылку.
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

    @Autowired
    private WarmupRepository warmups;

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
        // Своя пара у второй Задачи: иначе ячейка «A × X» стояла бы и в её форме,
        // и каждое значение считалось бы дважды.
        other = library.problem(library.topic(), library.method());
        assignment = issue(problem, other);
    }

    /** Ячейки в форме приёма: у каждой Задачи, все «без изменения»; экран по-прежнему под телефон. */
    @Test
    void receiptFormsCarryTheCellsOfTheirProblems() {
        String body = teacher.get(screen()).body();

        assertThat(body)
                .contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .contains("Владение");
        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 0 из 0 · разминки: 0 из 0")).as("четыре ячейки и одна у второй Задачи").isEqualTo(5);
        assertThat(count(body, ">без изменения<")).isEqualTo(5);
        assertThat(count(body, "Принять Работу")).isEqualTo(2);
        assertThat(body).doesNotContain("Вердикт и владение");
    }

    /** Сценарий «Приём с отметкой»: Работа и отметка одним действием, нетронутые ячейки прежние. */
    @Test
    void receiptWritesTheWorkAndTheChosenMark() {
        Browser.Page posted = teacher.postMultipart(receiptOf(problem), cells(
                        cell(topicA, methodX, "MASTERED"),
                        cell(topicA, methodY, ""),
                        cell(topicB, methodX, ""),
                        cell(topicB, methodY, "")),
                List.of(photo()));

        assertThat(posted.redirectsTo(screen())).isTrue();
        assertThat(works.findByAssignment(owner, assignment)).hasSize(1);
        String body = teacher.get(screen()).body();
        assertThat(count(body, "сейчас: владеет ·")).isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN)).as("три нетронутые и одна у второй Задачи").isEqualTo(4);
        assertThat(marks.countByStudent(owner, student)).isEqualTo(1);
    }

    /** Сценарий «Приём без файла не пишет отметок»: ни Работы, ни отметки, «верно» и «владеет» выбраны снова. */
    @Test
    void receiptWithoutAFileWritesNothingAndKeepsTheChoice() {
        List<Map.Entry<String, String>> fields = new ArrayList<>(List.of(
                Map.entry("verdict", "CORRECT"), Map.entry("note", "посмотрю позже")));
        fields.addAll(cells(
                cell(topicA, methodX, "MASTERED"),
                cell(topicA, methodY, ""),
                cell(topicB, methodX, ""),
                cell(topicB, methodY, "")));

        Browser.Page refused = teacher.postMultipart(receiptOf(problem), fields, List.of());

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Нужен хотя бы один файл").contains("посмотрю позже");
        assertThat(works.findByAssignment(owner, assignment)).isEmpty();
        assertThat(marks.countByStudent(owner, student)).isZero();
        assertThat(count(refused.body(), Pattern.compile("value=\"CORRECT\"\\s+checked"))).isEqualTo(1);
        assertThat(count(refused.body(), Pattern.compile("value=\"MASTERED\"\\s+selected"))).isEqualTo(1);
    }

    /** Сценарий «Один блок у принятой Работы»: «Вердикт и владение», без отдельного «Владения». */
    @Test
    void acceptedWorkHasOneVerdictAndMasteryBlock() {
        receive(assignment, problem);

        String body = teacher.get(screen()).body();

        assertThat(count(body, ">Вердикт и владение<")).isEqualTo(1);
        assertThat(body).doesNotContain(">Владение</summary>").doesNotContain("Сохранить отметки");
        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 0 из 0 · разминки: 0 из 0")).as("четыре в блоке и одна в приёме второй").isEqualTo(5);
        assertThat(count(body, "Принять Работу")).as("у второй Задачи — форма приёма").isEqualTo(1);
    }

    /** Сценарии «Выборочная простановка» и «Снятие отметки» формой вердикта: две меняются, две прежние; «неизвестно» снимает. */
    @Test
    void savingTwoCellsLeavesTheOtherTwoAndUnknownClears() {
        StudentWorkId work = receive(assignment, problem);

        Browser.Page posted = teacher.postForm(verdictOf(work), review("",
                cell(topicA, methodX, "MASTERED"),
                cell(topicA, methodY, ""),
                cell(topicB, methodX, "NOT_MASTERED"),
                cell(topicB, methodY, "")));

        assertThat(posted.redirectsTo(screen())).as("после сохранения — назад на экран приёма").isTrue();
        String body = teacher.get(screen()).body();
        assertThat(count(body, "сейчас: владеет ·")).isEqualTo(1);
        assertThat(count(body, "сейчас: не владеет ·")).isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN)).as("две нетронутые и одна у второй Задачи").isEqualTo(3);
        assertThat(marks.countByStudent(owner, student)).isEqualTo(2);

        teacher.postForm(verdictOf(work), review("", cell(topicA, methodX, "UNKNOWN")));

        body = teacher.get(screen()).body();
        assertThat(count(body, "сейчас: владеет ·")).isZero();
        assertThat(count(body, UNKNOWN_SHOWN)).isEqualTo(4);
        assertThat(marks.countByStudent(owner, student)).as("строка снята, а не записана как UNKNOWN").isEqualTo(1);
    }

    /** Сценарий «Справка считает проверенные Работы Ученика»: три проверенные и одна нет по другому Заданию. */
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

        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 2 из 3 · разминки: 0 из 0")).as("«A × X»").isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 0 из 0 · разминки: 0 из 0")).as("остальные три и одна у второй Задачи").isEqualTo(4);
    }

    /**
     * Сценарий «Справка считает итоги Разминок порознь»: верная Работа
     * по Задаче и два итога Разминок — «справился» и «не справился» —
     * по Задаче с парой «A × X», третья клетка пустая.
     */
    @Test
    void hintShowsWarmupOutcomesApartFromWorks() {
        works.setVerdict(owner, receive(assignment, problem), Verdict.CORRECT, "");
        ProblemId atTheBoard = library.problem(topicA, methodX);
        WarmupId monday = warmups.create(owner, LocalDate.of(2026, 10, 19), null, List.of(student), List.of(atTheBoard));
        WarmupId tuesday = warmups.create(owner, LocalDate.of(2026, 10, 20), null, List.of(student), List.of(atTheBoard));
        warmups.putOutcome(owner, monday, student, atTheBoard, WarmupOutcome.SUCCEEDED);
        warmups.putOutcome(owner, tuesday, student, atTheBoard, WarmupOutcome.FAILED);
        warmups.create(owner, LocalDate.of(2026, 10, 21), null, List.of(student), List.of(atTheBoard));

        String body = teacher.get(screen()).body();

        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 1 из 1 · разминки: 1 из 2")).as("«A × X»").isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 1 из 1 · разминки: 0 из 0")).as("прочие три пары Задачи").isEqualTo(3);
    }

    /** Сценарий «Вердикт не влияет на отметки»: «верно» с нетронутыми ячейками меняет справку, но не ячейки (ADR-0014). */
    @Test
    void verdictChangesTheHintButNotTheCells() {
        StudentWorkId work = receive(assignment, problem);
        teacher.postForm(verdictOf(work), review("", cell(topicA, methodX, "MASTERED")));

        teacher.postForm(verdictOf(work), review("CORRECT",
                cell(topicA, methodX, ""),
                cell(topicA, methodY, ""),
                cell(topicB, methodX, ""),
                cell(topicB, methodY, "")));

        String body = teacher.get(screen()).body();
        assertThat(body).contains("Вердикт: верно");
        assertThat(count(body, "сейчас: владеет · Работы: 1 из 1 · разминки: 0 из 0")).isEqualTo(1);
        assertThat(count(body, UNKNOWN_SHOWN + " Работы: 1 из 1 · разминки: 0 из 0")).as("три ячейки так и не тронуты").isEqualTo(3);
        assertThat(marks.countByStudent(owner, student)).isEqualTo(1);
    }

    /** Сценарий «Отказ в отметке не меняет вердикта»: пара не из разметки — тот же экран, вердикт прежний. */
    @Test
    void refusalIsShownOnTheSameScreenAndKeepsTheVerdict() {
        StudentWorkId work = receive(assignment, problem);
        SolutionMethodId stranger = library.method();

        Browser.Page refused = teacher.postForm(verdictOf(work), review("CORRECT", cell(topicA, stranger, "MASTERED")));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Ячейки нет").contains("Вердикт и владение");
        assertThat(works.findByAssignment(owner, assignment).get(0).verdict()).as("вердикт прежний").isNull();
        assertThat(marks.countByStudent(owner, student)).isZero();
        assertThat(teacher.get(screen()).body()).contains("не проверена");
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

    private String receiptOf(ProblemId problem) {
        return "/works?assignment=" + assignment.value() + "&problem=" + problem.value();
    }

    private static String verdictOf(StudentWorkId work) {
        return "/works/" + work.value() + "/verdict";
    }

    /** Поля формы «Вердикт и владение»: вердикт, пустое примечание и ячейки. */
    @SafeVarargs
    private static List<Map.Entry<String, String>> review(String verdict, List<Map.Entry<String, String>>... cells) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("verdict", verdict));
        fields.add(Map.entry("note", ""));
        fields.addAll(cells(cells));
        return fields;
    }

    @SafeVarargs
    private static List<Map.Entry<String, String>> cells(List<Map.Entry<String, String>>... cells) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        for (List<Map.Entry<String, String>> cell : cells) {
            fields.addAll(cell);
        }
        return fields;
    }

    private static Browser.FilePart photo() {
        try (InputStream in = MasteryScreenTest.class.getResourceAsStream("/ru/locus/file/rotated.jpg")) {
            if (in == null) {
                throw new IllegalStateException("Нет тестового ресурса rotated.jpg");
            }
            return new Browser.FilePart("files", "photo.jpg", FileType.JPEG, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private static int count(String body, Pattern pattern) {
        return (int) pattern.matcher(body).results().count();
    }
}
