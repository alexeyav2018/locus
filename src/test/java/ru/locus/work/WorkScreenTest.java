package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
import ru.locus.file.FileType;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;

/**
 * Требование «Приём идёт с экрана Задания и рассчитан на телефон»: экран
 * приёма по Заданию, приём двух снимков одним полем, вердикт позже,
 * добавление и удаление файлов, отказ на последнем, удаление Работы,
 * список Работ Ученика и ссылки со страниц Задания и Ученика.
 *
 * Сравнивается видимый человеку текст, а не разметка целиком
 * (antipatterns.md); исключение — {@code viewport} и {@code multiple}
 * в разметке: это и есть проверяемое свойство «под телефон».
 *
 * Обстановка — через репозитории с владельцем из учётной записи, как
 * в {@code AssignmentScreenTest}. Файлы — настоящие снимки через настоящий
 * multipart с несколькими частями в одном поле: разбор запроса
 * приложением иначе никто не проверит.
 */
class WorkScreenTest extends IntegrationTest {

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern FILE_LINK = Pattern.compile("href=\"[^\"]*/file/[^\"]*signature=[^\"]*\"");

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

    private TestAccounts.Account account;
    private Browser teacher;
    private StudentId student;
    private String studentName;
    private ProblemId first;
    private ProblemId second;
    private AssignmentId assignment;

    @BeforeEach
    void setTheScene() {
        account = accounts.settled(Role.TEACHER);
        teacher = new Browser(port);
        teacher.logIn(account.login(), account.password());
        studentName = TestLibrary.unique("Иванов Пётр");
        student = students.create(account.id(), studentName);
        first = library.problem(library.topic());
        second = library.problem(library.topic());
        LocalDate today = LocalDate.now();
        assignment = assignments.create(account.id(), student, null, today, today.plusDays(7), TheoryScope.NONE,
                List.of(first, second));
    }

    /** Сценарий «Экран приёма по Заданию» до приёма: форма у каждой Задачи, под телефон. */
    @Test
    void screenShowsAFormForEveryProblemAndFitsAPhone() {
        Browser.Page page = teacher.get(screen());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body())
                .contains("Работы: " + studentName)
                .contains("№ " + first.value())
                .contains("№ " + second.value())
                .contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .contains("accept=\"image/*,application/pdf\"");
        assertThat(count(page.body(), "Принять Работу")).as("форма приёма у каждой из двух Задач").isEqualTo(2);
        assertThat(count(page.body(), "multiple")).isGreaterThanOrEqualTo(2);
    }

    /** Сценарии «Приём Работы с двумя снимками» и «Экран приёма по Заданию» после приёма. */
    @Test
    void twoPhotosInOneFieldMakeAWorkWhileTheOtherProblemKeepsItsForm() {
        Browser.Page posted = receive(first, twoPhotos());

        assertThat(posted.redirectsTo(screen())).as("после приёма — назад на экран приёма").isTrue();
        String body = teacher.get(screen()).body();
        assertThat(body)
                .contains("получена " + LocalDate.now().format(SHOWN))
                .contains("не проверена")
                .contains("файл 1")
                .contains("файл 2")
                .doesNotContain("файл 3");
        assertThat(fileLinks(body)).as("две временные подписанные ссылки").isEqualTo(2);
        assertThat(count(body, "Принять Работу")).as("у второй Задачи по-прежнему форма").isEqualTo(1);
        assertThat(body).contains("Добавить файлы").contains("Удалить Работу");
    }

    /** Сценарий «Вердикт позже приёма». */
    @Test
    void verdictAndNoteSetLaterAreShown() {
        receive(first, twoPhotos());
        StudentWorkId id = onlyWork();

        Browser.Page posted = teacher.postForm("/works/" + id.value() + "/verdict",
                Map.of("verdict", "INCORRECT", "note", "потерян корень"));

        assertThat(posted.redirectsTo(screen())).isTrue();
        String body = teacher.get(screen()).body();
        assertThat(body).contains("Вердикт: неверно").contains("Примечание: потерян корень").doesNotContain("не проверена");
    }

    /** Сценарии «Добавление файлов», «Удаление файла», «Последний файл не удаляется». */
    @Test
    void filesAreAddedAndDeletedExceptTheLast() {
        receive(first, List.of(photo()));
        StudentWorkId id = onlyWork();

        teacher.postMultipart("/works/" + id.value() + "/files", Map.of(), twoPhotos());
        String body = teacher.get(screen()).body();
        assertThat(body).contains("файл 3");
        assertThat(fileLinks(body)).isEqualTo(3);

        List<StudentWorkFile> files = works.findById(account.id(), id).orElseThrow().files();
        teacher.postForm("/works/" + id.value() + "/files/" + files.get(0).id().value() + "/deletion", Map.of());
        teacher.postForm("/works/" + id.value() + "/files/" + files.get(1).id().value() + "/deletion", Map.of());
        body = teacher.get(screen()).body();
        assertThat(fileLinks(body)).isEqualTo(1);

        Browser.Page refused = teacher.postForm(
                "/works/" + id.value() + "/files/" + files.get(2).id().value() + "/deletion", Map.of());
        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Работа без файла не существует");
        assertThat(fileLinks(refused.body())).as("файл остался").isEqualTo(1);
    }

    /** Сценарии «Удаление Работы» и «Работа без файла». */
    @Test
    void deletedWorkGivesTheFormBackAndEmptyReceiptIsRefused() {
        receive(first, twoPhotos());
        StudentWorkId id = onlyWork();

        teacher.postForm("/works/" + id.value() + "/deletion", Map.of());
        String body = teacher.get(screen()).body();
        assertThat(count(body, "Принять Работу")).as("снова две формы приёма").isEqualTo(2);
        assertThat(fileLinks(body)).isZero();

        Browser.Page refused = receive(first, List.of());
        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Нужен хотя бы один файл");
        assertThat(works.findByAssignment(account.id(), assignment)).isEmpty();
    }

    /** Сценарий «Работы Ученика»: две Работы по двум Заданиям, новые первыми, ссылки на экран приёма. */
    @Test
    void worksOfStudentAreListedNewestFirstWithLinksToTheirAssignments() {
        LocalDate today = LocalDate.now();
        AssignmentId older = assignments.create(account.id(), student, null, today, today.plusDays(7),
                TheoryScope.NONE, List.of(second));
        teacher.postMultipart("/works?assignment=" + older.value() + "&problem=" + second.value(),
                Map.of("receivedOn", today.minusDays(3).toString()), List.of(photo()));
        receive(first, List.of(photo()));

        Browser.Page page = teacher.get("/works?student=" + student.value());

        assertThat(page.status()).isEqualTo(200);
        String body = page.body();
        assertThat(body)
                .contains("<meta name=\"viewport\"")
                .contains("Работы: " + studentName)
                .contains("Задача № " + first.value())
                .contains("Задача № " + second.value())
                .contains("/works?assignment=" + assignment.value())
                .contains("/works?assignment=" + older.value());
        assertThat(body.indexOf("Задача № " + first.value()))
                .as("новая Работа — первой")
                .isLessThan(body.indexOf("Задача № " + second.value()));
        assertThat(count(body, "не проверена")).isEqualTo(2);
    }

    /** Ссылки «Работы» на странице Задания и «Работы Ученика» на карточке Ученика ведут на эти экраны. */
    @Test
    void assignmentPageAndStudentCardLeadToTheWorkScreens() {
        assertThat(teacher.get("/assignments/" + assignment.value()).body())
                .contains("href=\"/works?assignment=" + assignment.value() + "\"")
                .contains(">Работы</a>");
        assertThat(teacher.get("/students/" + student.value()).body())
                .contains("href=\"/works?student=" + student.value() + "\"")
                .contains("Работы Ученика");
        assertThat(teacher.get("/works").redirectsTo("/assignments")).as("без параметров — к Заданиям").isTrue();
    }

    private Browser.Page receive(ProblemId problem, List<Browser.FilePart> files) {
        return teacher.postMultipart("/works?assignment=" + assignment.value() + "&problem=" + problem.value(),
                Map.of(), files);
    }

    private String screen() {
        return "/works?assignment=" + assignment.value();
    }

    private StudentWorkId onlyWork() {
        List<StudentWork> found = works.findByAssignment(account.id(), assignment);
        assertThat(found).hasSize(1);
        return found.get(0).id();
    }

    private static List<Browser.FilePart> twoPhotos() {
        return List.of(photo(), photo());
    }

    private static Browser.FilePart photo() {
        return new Browser.FilePart("files", "photo.jpg", FileType.JPEG, WorkFilesTest.resource("rotated.jpg"));
    }

    private static int fileLinks(String body) {
        Matcher matcher = FILE_LINK.matcher(body);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
