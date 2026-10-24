package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.Role;

/**
 * Сервис Задачи с текстом (design.md изменения {@code problem-text},
 * Р4, Р5, Р7, Р9, Р10): заведение, правка по слоту с версией, запись
 * текста Задаче без текста, Улики и предпросмотр.
 *
 * Файлы проверяются чтением из хранилища по ключу, а «ничего не положено» —
 * хранилищем-наблюдателем: ключей неудавшейся Задачи иначе не существует
 * нигде.
 */
class ProblemTextServiceTest extends IntegrationTest {

    private static final String CONDITION = "Решите уравнение $\\log_2 (x+1) = 3$.";
    private static final String SOLUTION = "По определению логарифма $x + 1 = 2^3$, откуда $x = 7$.";

    @Autowired
    private ProblemService problems;

    @Autowired
    private ProblemRepository repository;

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private SolutionMethodService methods;

    @Autowired
    private CharacteristicService characteristics;

    @Autowired
    private FileStorage storage;

    @Autowired
    private AssemblyDraftService drafts;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TestAccounts accounts;

    @BeforeEach
    void logIn() {
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарии «Задача с текстом заведена» и «Без Улик». */
    @Test
    void problemWithTextGetsBothRenderingsInItsSlotsAndNoEvidence() throws IOException {
        ProblemId id = withText(CONDITION, SOLUTION, null, null);

        Problem created = problems.problem(id);
        assertThat(created.hasText()).isTrue();
        assertThat(created.text().condition()).isEqualTo(CONDITION);
        assertThat(created.text().version()).isZero();
        assertThat(textOf(created.conditionFile())).contains("уравнение");
        assertThat(textOf(created.solutionFile())).contains("логарифма");
        assertThat(created.text().conditionEvidence()).as("поля слотов не тронуты — Улик нет").isNull();
        assertThat(created.text().solutionEvidence()).isNull();
    }

    /**
     * Сценарий «Задача с Уликой условия»: собранное поле слота стало Уликой,
     * а не PDF слота; готовый файл в поле решения — Уликой решения байт
     * в байт. Черновик после сохранения убран.
     */
    @Test
    void slotFieldsOfAProblemWithTextBecomeItsEvidence() throws IOException {
        PdfAssemblyOrder photo = photoOfASheet();
        byte[] solutionScan = TestLibrary.pdf();

        ProblemId id = withText(CONDITION, SOLUTION, photo, new UploadedFile(solutionScan, FileType.PDF));

        Problem created = problems.problem(id);
        byte[] conditionEvidence = read(created.text().conditionEvidence());
        assertThat(PdfAssembly.isPdf(conditionEvidence)).as("снимок собран в PDF").isTrue();
        assertThat(textOf(created.conditionFile())).as("PDF условия — отрисовка текста").contains("уравнение");
        assertThat(read(created.text().solutionEvidence())).isEqualTo(solutionScan);
        assertThatThrownBy(() -> drafts.assemble(photo)).as("черновик сборки убран после сохранения")
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Сценарий «Неизвестная команда в формуле»: отказ называет формулу,
     * и хранилище не получило ни отрисовки, ни Улики — отрисовка идёт
     * до укладки (Р10).
     */
    @Test
    void unparseableFormulaIsRefusedBeforeAnythingIsStored() {
        List<FileKey> stored = new ArrayList<>();
        ProblemService watched = watched(stored);

        assertThatThrownBy(() -> watched.create(ExamPart.FIRST,
                List.of(library.topic()), List.of(library.method()), List.of(),
                CONDITION, "Отсюда $\\foo{x}$.", null, new UploadedFile(TestLibrary.pdf(), FileType.PDF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\\foo{x}");

        assertThat(stored).as("в хранилище не появилось новых файлов").isEmpty();
    }

    /** Сценарий «Только один текст»: отказ называет недостающий текст, ничего не положено. */
    @Test
    void singleTextIsRefused() {
        List<FileKey> stored = new ArrayList<>();

        assertThatThrownBy(() -> watched(stored).create(ExamPart.FIRST,
                List.of(library.topic()), List.of(library.method()), List.of(),
                CONDITION, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("решения");

        assertThat(stored).isEmpty();
    }

    /** Сценарий «Текст решения поправлен»: условие байт в байт, прежняя отрисовка решения удалена. */
    @Test
    void editingTheSolutionRerendersOnlyItsSlot() throws IOException {
        ProblemId id = withText(CONDITION, SOLUTION, null, null);
        Problem before = problems.problem(id);
        byte[] condition = read(before.conditionFile());

        problems.editText(id, ProblemSlot.SOLUTION, "Ответ: $x = 7$, проверка подстановкой.", 0);

        Problem after = problems.problem(id);
        assertThat(after.id()).as("номер не изменился").isEqualTo(id);
        assertThat(after.text().solution()).isEqualTo("Ответ: $x = 7$, проверка подстановкой.");
        assertThat(after.text().version()).isEqualTo(1);
        assertThat(textOf(after.solutionFile())).contains("подстановкой");
        assertThat(read(after.conditionFile())).as("PDF условия прежний, байт в байт").isEqualTo(condition);
        assertThat(storage.read(before.solutionFile())).as("прежняя отрисовка решения удалена").isEmpty();
    }

    /**
     * Сценарий «Текст изменился после открытия страницы»: правка со старой
     * версией отклоняется, текст — записанный последним, а отрисовка
     * отклонённой правки в хранилище не осталась.
     */
    @Test
    void editWithAStaleVersionIsRefusedAndTheLastTextStays() {
        ProblemId id = withText(CONDITION, SOLUTION, null, null);
        problems.editText(id, ProblemSlot.CONDITION, "Решите уравнение $x^2 = 4$.", 0);
        Problem current = problems.problem(id);
        List<FileKey> stored = new ArrayList<>();

        assertThatThrownBy(() -> watched(stored).editText(id, ProblemSlot.CONDITION, "Решите $x = 1$.", 0))
                .isInstanceOf(ProblemTextChangedException.class)
                .hasMessageContaining("перечитайте");

        assertThat(problems.problem(id)).as("текст — записанный последним").isEqualTo(current);
        assertThat(stored).as("отрисовка отклонённой правки убрана").isEmpty();
    }

    /** Сценарий «Текст записан»: прежние PDF — Улики своих слотов, байт в байт. */
    @Test
    void writingTextTurnsTheFormerPdfsIntoEvidence() throws IOException {
        byte[] condition = TestLibrary.pdf();
        byte[] solution = TestLibrary.pdf();
        ProblemId id = problems.create(ExamPart.SECOND, List.of(library.topic()), List.of(library.method()),
                List.of(), new UploadedFile(condition, FileType.PDF), new UploadedFile(solution, FileType.PDF));
        Problem before = problems.problem(id);

        problems.writeText(id, CONDITION, SOLUTION);

        Problem after = problems.problem(id);
        assertThat(after.hasText()).isTrue();
        assertThat(textOf(after.conditionFile())).contains("уравнение");
        assertThat(textOf(after.solutionFile())).contains("логарифма");
        assertThat(read(after.text().conditionEvidence())).isEqualTo(condition);
        assertThat(read(after.text().solutionEvidence())).isEqualTo(solution);
        assertThat(after.part()).isEqualTo(before.part());
        assertThat(after.topics()).isEqualTo(before.topics());
        assertThat(after.methods()).isEqualTo(before.methods());
    }

    /** Записать текст можно только Задаче без текста; у Задачи с текстом — правка по слотам. */
    @Test
    void textIsWrittenOnlyToAProblemWithoutText() {
        ProblemId id = withText(CONDITION, SOLUTION, null, null);

        assertThatThrownBy(() -> problems.writeText(id, CONDITION, SOLUTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("текст уже есть");
    }

    /** Сценарий «Улика заменена»: новая открывается, прежней нет, PDF решения байт в байт. */
    @Test
    void replacedEvidenceLeavesTheSlotPdfAlone() {
        ProblemId id = withText(CONDITION, SOLUTION, null, new UploadedFile(TestLibrary.pdf(), FileType.PDF));
        Problem before = problems.problem(id);
        byte[] solution = read(before.solutionFile());
        byte[] replacement = TestLibrary.pdf();

        problems.replaceEvidence(id, ProblemSlot.SOLUTION, new UploadedFile(replacement, FileType.PDF));

        Problem after = problems.problem(id);
        assertThat(read(after.text().solutionEvidence())).isEqualTo(replacement);
        assertThat(storage.read(before.text().solutionEvidence())).as("прежняя Улика удалена").isEmpty();
        assertThat(read(after.solutionFile())).as("PDF решения прежний").isEqualTo(solution);
        assertThat(after.text().version()).as("Улика — не текст: версия не растёт").isZero();
    }

    /** Удаление Задачи с текстом уносит и Улики. */
    @Test
    void deletingAProblemWithTextTakesItsEvidenceAway() {
        ProblemId id = withText(CONDITION, SOLUTION,
                new UploadedFile(TestLibrary.pdf(), FileType.PDF), new UploadedFile(TestLibrary.pdf(), FileType.PDF));
        Problem problem = problems.problem(id);

        problems.delete(id);

        assertThat(storage.read(problem.text().conditionEvidence())).isEmpty();
        assertThat(storage.read(problem.text().solutionEvidence())).isEmpty();
        assertThat(storage.read(problem.conditionFile())).isEmpty();
    }

    /**
     * Сценарии «Формула видна в предпросмотре» и «Предпросмотр с ошибкой»
     * на уровне сервиса: отрисовка в памяти, ни одного файла; пустой текст
     * слота не отрисовывается.
     */
    @Test
    void previewRendersInMemoryAndStoresNothing() throws IOException {
        List<FileKey> stored = new ArrayList<>();
        ProblemService watched = watched(stored);

        RenderedText rendered = watched.preview("Найдите $\\frac{1}{2}$ от числа.", "");

        assertThat(textOf(rendered.condition())).contains("Найдите");
        assertThat(rendered.solution()).as("пустое решение не отрисовывается").isNull();
        assertThatThrownBy(() -> watched.preview("Отсюда $\\foo$.", SOLUTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\\foo");
        assertThat(stored).as("предпросмотр ничего не кладёт").isEmpty();
    }

    private ProblemId withText(String condition, String solution, ProblemPdf conditionEvidence,
                               ProblemPdf solutionEvidence) {
        return problems.create(ExamPart.SECOND, List.of(library.topic()), List.of(library.method()), List.of(),
                condition, solution, conditionEvidence, solutionEvidence);
    }

    /**
     * Сервис на настоящем хранилище, помнящем положенное. Права здесь
     * не проверяются — это экземпляр мимо Spring, — а проверяется только
     * порядок укладки.
     */
    private ProblemService watched(List<FileKey> stored) {
        return new ProblemService(repository, taxonomy, methods, characteristics,
                new WatchedStorage(storage, stored), drafts, List.of());
    }

    private PdfAssemblyOrder photoOfASheet() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png);
        AssemblyDraft photo = drafts.upload("лист.png", new ByteArrayInputStream(png.toByteArray()));
        return new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(photo.id(), 1, 1)));
    }

    private byte[] read(FileKey key) {
        return storage.read(key).orElseThrow(() -> new AssertionError("файла нет в хранилище: " + key));
    }

    private String textOf(FileKey key) throws IOException {
        return textOf(read(key));
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Настоящее хранилище, помнящее, что в нём сейчас лежит из положенного этим тестом. */
    private record WatchedStorage(FileStorage real, List<FileKey> stored) implements FileStorage {

        @Override
        public FileKey put(byte[] content, String contentType) {
            FileKey key = real.put(content, contentType);
            stored.add(key);
            return key;
        }

        @Override
        public URI temporaryLink(FileKey key) {
            return real.temporaryLink(key);
        }

        @Override
        public Optional<byte[]> read(FileKey key) {
            return real.read(key);
        }

        @Override
        public void delete(FileKey key) {
            real.delete(key);
            stored.remove(key);
        }
    }
}
