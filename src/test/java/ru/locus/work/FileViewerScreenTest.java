package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileType;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Требование «Файлы просматриваются внутри системы» (interface-navigation):
 * снимок — картинкой, PDF — картинками страниц с кнопкой «Открыть PDF»
 * (подробно — {@code PdfPagesOnTheViewerTest}),
 * «Назад» ведёт по {@code from}; чужая Работа неотличима от несуществующей.
 */
class FileViewerScreenTest extends IntegrationTest {

    private static final Pattern VIEWER_LINK = Pattern.compile("href=\"(/works/\\d+/files/\\d+)[^\"]*\"");

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

    private TestAccounts.Account account;
    private Browser teacher;
    private AssignmentId assignment;
    private ProblemId problem;
    private TaxonomyNodeId topic;

    @BeforeEach
    void setTheScene() {
        LoggedIn.as(Role.ADMINISTRATOR);
        account = accounts.settled(Role.TEACHER);
        teacher = new Browser(port);
        teacher.logIn(account.login(), account.password());
        StudentId student = students.create(account.id(), TestLibrary.unique("Иванов Пётр"));
        topic = library.topic();
        problem = library.problem(topic);
        LocalDate today = LocalDate.now();
        assignment = assignments.create(account.id(), student, null, today, today.plusDays(7), TheoryScope.NONE,
                List.of(problem));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Снимок решения». */
    @Test
    void aWorkFileOpensAsAPictureWithABackButToTheWorkScreen() {
        teacher.postMultipart("/works?assignment=" + assignment.value() + "&problem=" + problem.value(), Map.of(),
                List.of(new Browser.FilePart("files", "photo.jpg", FileType.JPEG, WorkFilesTest.resource("rotated.jpg"))));
        String screen = teacher.get("/works?assignment=" + assignment.value()).body();
        Matcher link = VIEWER_LINK.matcher(screen);
        assertThat(link.find()).as("на экране приёма есть ссылка на просмотр файла").isTrue();

        Browser.Page viewer = teacher.get(link.group(1));

        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.body())
                .contains("<img")
                .contains("signature=")
                .doesNotContain("<object")
                .contains("href=\"/works?assignment=" + assignment.value() + "\">← Назад</a>");
    }

    /** Сценарий «PDF решения Задачи». */
    @Test
    void aProblemPdfOpensAsPageImagesWithTheOpenButton() {
        ProblemId renderable = library.renderableProblem(topic);

        Browser.Page viewer = teacher.get("/problems/" + renderable.value() + "/solution?from=/problems?part%3DFIRST");

        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.body())
                .contains("src=\"/problems/" + renderable.value() + "/solution/pages/1\"")
                .contains("Открыть PDF")
                .contains("<a href=\"/problems?part=FIRST\">← Назад</a>")
                .doesNotContain("<object");
    }

    /** Сценарий «Чужой файл Работы». */
    @Test
    void anotherTeachersWorkFileIsAsMissingAsANonexistentOne() {
        teacher.postMultipart("/works?assignment=" + assignment.value() + "&problem=" + problem.value(), Map.of(),
                List.of(new Browser.FilePart("files", "photo.jpg", FileType.JPEG, WorkFilesTest.resource("rotated.jpg"))));
        Matcher link = VIEWER_LINK.matcher(teacher.get("/works?assignment=" + assignment.value()).body());
        assertThat(link.find()).isTrue();

        TestAccounts.Account other = accounts.settled(Role.TEACHER);
        Browser stranger = new Browser(port);
        stranger.logIn(other.login(), other.password());

        Browser.Page foreign = stranger.get(link.group(1));
        Browser.Page nonexistent = stranger.get("/works/999999999/files/999999999");

        assertThat(foreign.status()).isEqualTo(404);
        assertThat(nonexistent.status()).isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("signature=").doesNotContain("<img");
    }

    @Test
    void headersAllowFramingFromTheSameOriginOnly() {
        Browser.Page page = teacher.get("/problems/" + problem.value());

        assertThat(page.frameOptions()).contains("SAMEORIGIN");
    }
}
