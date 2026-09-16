package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentService;
import ru.locus.assignment.TheoryScope;
import ru.locus.file.FileType;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentService;
import ru.locus.user.Role;

/**
 * Задача 5.2: требование «Файлы Работы — изображение или PDF, наружу только
 * по временной ссылке» — на локальном хранилище, по образцу
 * {@link ru.locus.problem.ProblemFilesTest}.
 *
 * Содержимое проверяется по-настоящему: файл достаётся по выданной ссылке,
 * тем же путём, каким его достанет браузер учителя. Что в хранилище ничего
 * не осталось после отказа, считается по папке
 * {@code locus.file.local.directory} до и после: ключей отклонённой Работы
 * не существует нигде.
 */
class WorkFilesTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private StudentWorkService service;

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private StudentService students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    @Value("${locus.file.local.directory}")
    private Path directory;

    private ProblemId problem;
    private AssignmentId assignment;

    @BeforeEach
    void logInAndIssue() {
        LoggedIn.as(accounts.settled(Role.TEACHER));
        problem = library.problem(library.topic());
        assignment = assignments.issueToStudent(students.create(TestLibrary.unique("Иванов Пётр")),
                List.of(problem), java.time.LocalDate.of(2026, 9, 30), TheoryScope.NONE);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Снимок пережат»: меньше исходного, JPEG, ориентация выправлена. */
    @Test
    void photoIsCompressedAndUpright() {
        byte[] large = resource("large.jpg");
        byte[] rotated = resource("rotated.jpg");
        StudentWorkId id = service.receive(assignment, problem, null, null, "",
                List.of(new UploadedWorkFile(large, FileType.JPEG), new UploadedWorkFile(rotated, FileType.JPEG)));

        List<LinkedFile> files = service.filesOf(service.work(id));
        Browser browser = anyone();
        byte[] storedLarge = browser.getBytes(files.get(0).link().toString());
        assertThat(storedLarge.length).as("снимок после приёма меньше исходного").isLessThan(large.length);
        assertThat(files.get(0).file().key().value()).endsWith(".jpg");
        BufferedImage upright = read(browser.getBytes(files.get(1).link().toString()));
        assertThat(upright.getHeight()).as("снимок, снятый повёрнутой камерой, стоит вертикально").isGreaterThan(upright.getWidth());
    }

    /** Сценарий «PDF как есть». */
    @Test
    void pdfComesBackByteForByte() {
        byte[] pdf = TestLibrary.pdf();
        StudentWorkId id = service.receive(assignment, problem, null, null, "",
                List.of(new UploadedWorkFile(pdf, FileType.PDF)));

        String link = service.filesOf(service.work(id)).get(0).link().toString();

        assertThat(anyone().getBytes(link)).isEqualTo(pdf);
    }

    /** Сценарий «Файл только по временной ссылке». */
    @Test
    void withoutAValidSignatureTheFileIsNotServed() {
        StudentWorkId id = service.receive(assignment, problem, null, null, "", List.of(jpeg()));
        String link = service.filesOf(service.work(id)).get(0).link().toString();
        String withoutSignature = link.substring(0, link.indexOf('?'));
        String withPostponedExpiry = link.replaceFirst("expires=\\d+", "expires=9999999999");

        Browser browser = anyone();
        assertThat(browser.get(withoutSignature).status()).as("постоянного адреса у файла Работы нет").isEqualTo(403);
        assertThat(browser.get(withPostponedExpiry).status()).as("срок входит в подпись").isEqualTo(403);
        assertThat(browser.get(link).status()).isEqualTo(200);
    }

    /** Сценарии «Удаление файла» и «Удаление Работы»: ранее выданные ссылки не отдают. */
    @Test
    void deletedFileAndDeletedWorkAreNotServedByEarlierLinks() {
        StudentWorkId id = service.receive(assignment, problem, null, null, "", List.of(jpeg(), jpeg(), jpeg()));
        List<LinkedFile> files = service.filesOf(service.work(id));
        String firstLink = files.get(0).link().toString();
        String secondLink = files.get(1).link().toString();
        String thirdLink = files.get(2).link().toString();
        Browser browser = anyone();

        service.deleteFile(id, files.get(0).file().id());
        assertThat(browser.getBytes(firstLink)).as("удалённый файл не отдаётся").isEmpty();
        assertThat(browser.getBytes(secondLink)).as("остальные на месте").isNotEmpty();

        service.delete(id);
        assertThat(browser.getBytes(secondLink)).as("после удаления Работы не отдаётся ни один").isEmpty();
        assertThat(browser.getBytes(thirdLink)).isEmpty();
    }

    /** Сценарий «Файл неподдерживаемого типа»: отклонён до укладки — в хранилище не появилось ничего. */
    @Test
    void unsupportedTypeIsRefusedBeforeAnythingIsStored() throws IOException {
        long before = filesInStorage();

        assertThatThrownBy(() -> service.receive(assignment, problem, null, null, "",
                List.of(jpeg(), new UploadedWorkFile("текст".getBytes(), "text/plain"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("изображения и PDF");

        assertThat(service.ofAssignment(assignment)).isEmpty();
        assertThat(filesInStorage()).as("ни один файл — и снимок тоже — не уложен").isEqualTo(before);
    }

    private long filesInStorage() throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private Browser anyone() {
        return new Browser(port);
    }

    private static UploadedWorkFile jpeg() {
        return new UploadedWorkFile(resource("rotated.jpg"), FileType.JPEG);
    }

    static byte[] resource(String name) {
        try (InputStream in = WorkFilesTest.class.getResourceAsStream("/ru/locus/file/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Нет тестового ресурса " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage read(byte[] image) {
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(image));
            assertThat(read).as("содержимое открывается как изображение").isNotNull();
            return read;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
