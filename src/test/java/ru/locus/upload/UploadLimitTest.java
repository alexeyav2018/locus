package ru.locus.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeAll;
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
import ru.locus.problem.ProblemService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.work.StudentWorkRepository;

/**
 * Требование «Предел загрузки поднят только для инструмента» (ADR-0044):
 * исходник сборки крупнее общего предела принимается, Работа и готовый
 * PDF Задачи того же объёма — нет.
 *
 * Запросы — настоящий multipart через настоящий контейнер: предел держат
 * два уровня, контейнер и перехватчик, и проверить их стык иначе нельзя.
 */
class UploadLimitTest extends IntegrationTest {

    /** Больше общего предела на файл (20 МБ), меньше предела инструмента. */
    private static final int OVER_COMMON_LIMIT = 25 * 1024 * 1024;

    private static byte[] largePdf;

    @LocalServerPort
    private int port;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private ProblemService problems;

    @Autowired
    private StudentRepository students;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    @BeforeAll
    static void makeALargePdf() throws IOException {
        largePdf = pdfOf(OVER_COMMON_LIMIT);
        assertThat(largePdf.length).isGreaterThan(OVER_COMMON_LIMIT);
    }

    /** Сценарий «Крупный сборник». */
    @Test
    void largeSourceForAssemblyIsAccepted() {
        Browser.Page row = loggedIn(Role.ADMINISTRATOR).postMultipart("/problems/drafts", Map.of("slot", "condition"),
                List.of(new Browser.FilePart("source", "сборник.pdf", FileType.PDF, largePdf)));

        assertThat(row.status()).isEqualTo(200);
        assertThat(row.body()).contains("сборник.pdf").contains("страниц: 1");
    }

    /** Сценарий «Работа того же объёма»: отказ текстом экрана приёма, как и прежде. */
    @Test
    void workOfTheSameSizeIsRefusedOnTheReceiptScreen() {
        TestAccounts.Account account = accounts.settled(Role.TEACHER);
        Browser teacher = logIn(account);
        StudentId student = students.create(account.id(), TestLibrary.unique("Иванов Пётр"));
        ProblemId problem = library.problem(library.topic());
        LocalDate today = LocalDate.now();
        AssignmentId assignment = assignments.create(account.id(), student, null, today, today.plusDays(7),
                TheoryScope.NONE, List.of(problem));

        Browser.Page refused = teacher.postMultipart(
                "/works?assignment=" + assignment.value() + "&problem=" + problem.value(), Map.of(),
                List.of(new Browser.FilePart("files", "scan.pdf", FileType.PDF, largePdf)));

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("Файлы слишком велики");
        assertThat(works.findByAssignment(account.id(), assignment)).isEmpty();
    }

    /** Готовый PDF Задачи держит общий предел: инструмент его не поднимает. */
    @Test
    void readyProblemPdfOfTheSameSizeIsRefused() {
        TaxonomyNodeId topic = library.topic();
        Browser.Page refused = loggedIn(Role.ADMINISTRATOR).postMultipart("/problems",
                List.of(Map.entry("part", "FIRST"),
                        Map.entry("topics", String.valueOf(topic.value())),
                        Map.entry("methodIds", String.valueOf(library.method().value()))),
                List.of(new Browser.FilePart("condition", "condition.pdf", FileType.PDF, largePdf),
                        new Browser.FilePart("solution", "solution.pdf", FileType.PDF, TestLibrary.pdf())));

        assertThat(refused.status()).isEqualTo(413);
        assertThat(problems.problemsOf(topic)).isEmpty();
    }

    /** Одностраничный PDF, раздутый несжимаемым потоком до заданного объёма. */
    private static byte[] pdfOf(int size) throws IOException {
        byte[] noise = new byte[size];
        new Random(41).nextBytes(noise);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            COSStream padding = document.getDocument().createCOSStream();
            try (OutputStream out = padding.createRawOutputStream()) {
                out.write(noise);
            }
            page.getCOSObject().setItem(COSName.getPDFName("Padding"), padding);
            ByteArrayOutputStream out = new ByteArrayOutputStream(size + 4096);
            document.save(out);
            return out.toByteArray();
        }
    }

    private Browser loggedIn(Role... roles) {
        return logIn(accounts.settled(roles));
    }

    private Browser logIn(TestAccounts.Account account) {
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }
}
