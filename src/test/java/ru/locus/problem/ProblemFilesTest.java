package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.Role;

/**
 * Требования «Файлы Задачи отдаются только по временной подписанной ссылке»
 * и «Файлы Задачи не остаются в хранилище без Задачи».
 *
 * Содержимое проверяется по-настоящему: файл достаётся тем же путём, каким
 * его достанет браузер учителя, — обращением по выданной ссылке. Второго пути
 * к содержимому в системе нет, и заводить его ради проверки нельзя: проверка
 * ушла бы проверять не то, что происходит в бою.
 */
class ProblemFilesTest extends IntegrationTest {

    /** Подпись длиннее колонки: база отвергнет запись — но уже после укладки файлов. */
    private static final String TOO_LONG_CAPTION = "я".repeat(400);

    @LocalServerPort
    private int port;

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
    private TestLibrary library;

    @Autowired
    private TestAccounts accounts;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /**
     * Задача 4.3: PDF отдаётся байт в байт. Пережатие применяется
     * к изображениям и никогда — к PDF: условие и решение хранятся ровно
     * такими, какими их дал Администратор.
     */
    @Test
    void pdfComesBackByteForByte() {
        byte[] condition = TestLibrary.pdf();
        byte[] solution = TestLibrary.pdf();
        ProblemId id = created(condition, solution);

        Browser browser = anyone();
        assertThat(browser.getBytes(problems.conditionLink(id).toString()))
                .as("PDF не пережимается: он возвращается тем же, чем был положен")
                .isEqualTo(condition);
        assertThat(browser.getBytes(problems.solutionLink(id).toString())).isEqualTo(solution);
    }

    /**
     * Задача 4.1: неудача сохранения записи не оставляет в хранилище
     * положенных файлов.
     *
     * Сбой настоящий, а не подстроенный подменой репозитория: подпись длиннее
     * колонки отвергается базой — то есть падает именно сохранение, уже после
     * укладки обоих файлов, ровно в том месте, ради которого написана уборка.
     * Хранилище при этом обёрнуто наблюдателем: узнать, что осталось внутри,
     * иначе неоткуда — ключей неудавшейся Задачи не существует нигде.
     */
    @Test
    void failedSavingLeavesNothingInTheStorage() {
        List<FileKey> stored = new ArrayList<>();
        ProblemService watched = new ProblemService(repository, taxonomy, methods, characteristics,
                new WatchedStorage(storage, stored), List.of());

        assertThatThrownBy(() -> watched.create(TOO_LONG_CAPTION, ExamPart.FIRST,
                List.of(library.topic()), List.of(library.method()), List.of(),
                pdf(TestLibrary.pdf()), pdf(TestLibrary.pdf())))
                .as("сохранение не удалось")
                .isInstanceOf(RuntimeException.class);

        assertThat(stored).as("положенное убрано: сирот в хранилище не осталось").isEmpty();
    }

    /** Сценарий «Замена PDF» и «Второй файл при замене не задет». */
    @Test
    void replacedSolutionIsServedWhileTheOldFileIsGoneAndTheConditionStays() {
        byte[] condition = TestLibrary.pdf();
        ProblemId id = created(condition, TestLibrary.pdf());
        String oldLink = problems.solutionLink(id).toString();
        byte[] replacement = TestLibrary.pdf();

        problems.replaceSolution(id, pdf(replacement));

        Browser browser = anyone();
        assertThat(browser.getBytes(problems.solutionLink(id).toString()))
                .as("Задача отдаёт новое решение")
                .isEqualTo(replacement);
        assertThat(browser.getBytes(oldLink))
                .as("прежний файл в хранилище не остаётся")
                .isEmpty();
        assertThat(browser.getBytes(problems.conditionLink(id).toString()))
                .as("PDF условия остаётся прежним, байт в байт")
                .isEqualTo(condition);
    }

    /** Замена условия — тем же порядком и с той же неприкосновенностью второго файла. */
    @Test
    void replacedConditionDoesNotTouchTheSolution() {
        byte[] solution = TestLibrary.pdf();
        ProblemId id = created(TestLibrary.pdf(), solution);
        byte[] replacement = TestLibrary.pdf();

        problems.replaceCondition(id, pdf(replacement));

        Browser browser = anyone();
        assertThat(browser.getBytes(problems.conditionLink(id).toString())).isEqualTo(replacement);
        assertThat(browser.getBytes(problems.solutionLink(id).toString())).isEqualTo(solution);
    }

    /** Сценарий «Удаление Задачи уносит оба файла». */
    @Test
    void deletingAProblemTakesBothFilesOutOfTheStorage() {
        ProblemId id = created(TestLibrary.pdf(), TestLibrary.pdf());
        String conditionLink = problems.conditionLink(id).toString();
        String solutionLink = problems.solutionLink(id).toString();

        problems.delete(id);

        Browser browser = anyone();
        assertThat(browser.getBytes(conditionLink))
                .as("ни условие, ни решение больше не отдаются по ранее выданным ссылкам")
                .isEmpty();
        assertThat(browser.getBytes(solutionLink)).isEmpty();
    }

    /**
     * Требование «наружу — только временная подписанная ссылка».
     *
     * Сценарий «Ссылка после истечения срока» проверяется тем же рубежом:
     * срок входит в подпись, поэтому отодвинуть его, не сломав подпись,
     * невозможно — а ссылка с чужим сроком отвергается так же, как беззнаковая.
     */
    @Test
    void withoutAValidSignatureTheFileIsNotServed() {
        ProblemId id = library.problem(library.topic());
        String link = problems.conditionLink(id).toString();
        String withoutSignature = link.substring(0, link.indexOf('?'));
        String withPostponedExpiry = link.replaceFirst("expires=\\d+", "expires=9999999999");

        Browser browser = anyone();
        assertThat(browser.get(withoutSignature).status())
                .as("постоянного адреса у файла Задачи нет")
                .isEqualTo(403);
        assertThat(browser.get(withPostponedExpiry).status())
                .as("срок входит в подпись: отодвинуть его не удаётся")
                .isEqualTo(403);
        assertThat(browser.get(link).status()).as("действующая ссылка работает").isEqualTo(200);
    }

    /** Сценарий «Просмотр Задачи»: обе ссылки на странице временные и подписанные. */
    @Test
    void bothLinksOnThePageAreSignedAndTemporary() {
        ProblemId id = library.problem(library.topic());

        String page = loggedIn(Role.TEACHER).get("/problems/" + id.value()).body();

        assertThat(page)
                .as("ссылка несёт срок и подпись, а не постоянный адрес")
                .contains("expires=")
                .contains("signature=");
    }

    private ProblemId created(byte[] condition, byte[] solution) {
        TaxonomyNodeId topic = library.topic();
        return problems.create(null, ExamPart.FIRST,
                List.of(topic), List.of(library.method()), List.of(), pdf(condition), pdf(solution));
    }

    private static UploadedFile pdf(byte[] content) {
        return new UploadedFile(content, FileType.PDF);
    }

    /** Браузер без входа: отдачу файла пропускает подпись, а не сеанс. */
    private Browser anyone() {
        return new Browser(port);
    }

    private Browser loggedIn(Role... roles) {
        TestAccounts.Account account = accounts.settled(roles);
        Browser browser = new Browser(port);
        browser.logIn(account.login(), account.password());
        return browser;
    }

    /**
     * Настоящее хранилище, помнящее, что в нём сейчас лежит из положенного
     * этим тестом. Подмены поведения нет — только наблюдение.
     */
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
        public void delete(FileKey key) {
            real.delete(key);
            stored.remove(key);
        }
    }
}
