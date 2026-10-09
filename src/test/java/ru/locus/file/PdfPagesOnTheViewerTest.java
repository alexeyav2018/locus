package ru.locus.file;

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
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.theory.TheoryMaterialId;
import ru.locus.user.Role;

/**
 * Требование «Файлы просматриваются внутри системы» (interface-navigation)
 * в части PDF: страница просмотра показывает картинки страниц, а не
 * встроенный просмотр (ADR-0052), и картинка каждой страницы выдаётся
 * под теми же правами, что сама страница просмотра.
 *
 * Самое тихое, что здесь может сломаться, — изоляция: картинка страницы
 * чужой Работы обязана быть неотличима от несуществующей, иначе адрес
 * картинки стал бы обходом страницы просмотра.
 */
class PdfPagesOnTheViewerTest extends IntegrationTest {

    private static final Pattern WORK_VIEWER = Pattern.compile("href=\"(/works/\\d+/files/\\d+)[^\"]*\"");
    private static final Pattern PAGE_IMAGE = Pattern.compile("<img[^>]*src=\"[^\"]*/pages/\\d+\"");

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
    private ProblemRepository problems;

    private TestAccounts.Account account;
    private Browser teacher;
    private TaxonomyNodeId topic;
    private ProblemId problem;

    @BeforeEach
    void setTheScene() {
        LoggedIn.as(Role.ADMINISTRATOR);
        account = accounts.settled(Role.TEACHER);
        teacher = new Browser(port);
        teacher.logIn(account.login(), account.password());
        topic = library.topic();
        problem = library.renderableProblem(topic);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарии «PDF решения Задачи» и «Браузер без просмотрщика PDF». */
    @Test
    void problemPdfsAreShownAsPageImages() {
        for (String slot : List.of("condition", "solution")) {
            Browser.Page viewer = teacher.get("/problems/" + problem.value() + "/" + slot);

            assertThat(viewer.status()).isEqualTo(200);
            assertThat(viewer.body())
                    .contains("src=\"/problems/" + problem.value() + "/" + slot + "/pages/1\"")
                    .contains("loading=\"lazy\"")
                    .contains("Открыть PDF")
                    .doesNotContain("<object")
                    .doesNotContain("/pages/2\"");
        }
    }

    /** Сценарий «Длинный PDF». */
    @Test
    void aLongTheoryPdfShowsTwentyPagesAndNamesTheTotal() {
        TheoryMaterialId material = library.material(topic, TestLibrary.unique("Длинный конспект"),
                TestLibrary.renderablePdf(30));

        String body = teacher.get("/theory/" + material.value() + "/view").body();

        assertThat(PAGE_IMAGE.matcher(body).results().count()).isEqualTo(PdfPages.SHOWN);
        assertThat(body)
                .contains("/theory/" + material.value() + "/view/pages/20\"")
                .doesNotContain("/pages/21\"")
                .contains("Показаны первые 20 из 30 страниц")
                .contains("Открыть PDF");
    }

    /** Сценарий «Неразбираемый PDF». */
    @Test
    void anUnreadablePdfLeavesTheMessageAndTheOpenButton() {
        TheoryMaterialId material = library.material(topic, TestLibrary.unique("Битый файл"));

        Browser.Page viewer = teacher.get("/theory/" + material.value() + "/view");

        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.body())
                .contains("Показать этот PDF на странице не удалось")
                .contains("Открыть PDF")
                .doesNotContain("/pages/1\"")
                .doesNotContain("<object");
    }

    @Test
    void aWorkPdfIsShownAsPageImages() {
        String viewerAddress = uploadedWorkPdf();

        Browser.Page viewer = teacher.get(viewerAddress);

        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.body())
                .contains("src=\"" + viewerAddress + "/pages/1\"")
                .doesNotContain("<object");
    }

    @Test
    void pageImagesOfTheLibraryAndOfOwnWorkAreJpegs() {
        TheoryMaterialId material = library.material(topic, TestLibrary.unique("Конспект"),
                TestLibrary.renderablePdf(2));
        String work = uploadedWorkPdf();

        for (String address : List.of(
                "/problems/" + problem.value() + "/condition/pages/1",
                "/problems/" + problem.value() + "/solution/pages/1",
                "/theory/" + material.value() + "/view/pages/2",
                work + "/pages/1")) {
            Browser.Page image = teacher.get(address);

            assertThat(image.status()).as(address).isEqualTo(200);
            assertThat(image.contentType()).as(address).startsWith("image/jpeg");
            assertThat(image.cacheControl()).as(address).hasValueSatisfying(value ->
                    assertThat(value).contains("private").contains("no-cache"));
            assertThat(image.etag()).as(address).isPresent();
        }
    }

    /** Сценарий «Картинка страницы чужой Работы». */
    @Test
    void aPageImageOfAnotherTeachersWorkIsAsMissingAsANonexistentOne() {
        String work = uploadedWorkPdf();
        TestAccounts.Account other = accounts.settled(Role.TEACHER);
        Browser stranger = new Browser(port);
        stranger.logIn(other.login(), other.password());

        Browser.Page foreign = stranger.get(work + "/pages/1");
        Browser.Page nonexistent = stranger.get("/works/999999999/files/999999999/pages/1");

        assertThat(teacher.get(work + "/pages/1").status()).isEqualTo(200);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(nonexistent.status()).isEqualTo(404);
        assertThat(foreign.contentType()).doesNotStartWith("image/");
    }

    /** Сценарий «Страница за пределами файла». */
    @Test
    void aPageBeyondTheFileIsMissing() {
        TheoryMaterialId link = library.linkedMaterial(topic, TestLibrary.unique("Ссылка"));

        assertThat(teacher.get("/problems/" + problem.value() + "/condition/pages/2").status()).isEqualTo(404);
        assertThat(teacher.get("/problems/" + problem.value() + "/condition/pages/0").status()).isEqualTo(404);
        assertThat(teacher.get("/theory/" + link.value() + "/view/pages/1").status()).isEqualTo(404);
    }

    @Test
    void aRepeatWithTheSameEtagIsNotModified() {
        String address = "/problems/" + problem.value() + "/condition/pages/1";
        String etag = teacher.get(address).etag().orElseThrow();

        Browser.Page repeat = teacher.getIfNoneMatch(address, etag);

        assertThat(repeat.status()).isEqualTo(304);
        assertThat(repeat.body()).isEmpty();
    }

    @Test
    void aReplacedConditionGetsAnotherEtag() {
        String address = "/problems/" + problem.value() + "/condition/pages/1";
        String before = teacher.get(address).etag().orElseThrow();

        problems.changeConditionFile(problem, library.stored(TestLibrary.renderablePdf(1)));
        Browser.Page after = teacher.getIfNoneMatch(address, before);

        assertThat(after.status()).isEqualTo(200);
        assertThat(after.etag()).hasValueSatisfying(etag -> assertThat(etag).isNotEqualTo(before));
    }

    /** Работа с одним PDF по Задаче своего Задания; адрес её страницы просмотра. */
    private String uploadedWorkPdf() {
        StudentId student = students.create(account.id(), TestLibrary.unique("Иванов Пётр"));
        LocalDate today = LocalDate.now();
        AssignmentId assignment = assignments.create(account.id(), student, null, today, today.plusDays(7),
                TheoryScope.NONE, List.of(problem));
        teacher.postMultipart("/works?assignment=" + assignment.value() + "&problem=" + problem.value(), Map.of(),
                List.of(new Browser.FilePart("files", "answer.pdf", FileType.PDF, TestLibrary.renderablePdf(1))));
        Matcher link = WORK_VIEWER.matcher(teacher.get("/works?assignment=" + assignment.value()).body());
        assertThat(link.find()).as("на экране приёма есть ссылка на просмотр файла").isTrue();
        return link.group(1);
    }
}
