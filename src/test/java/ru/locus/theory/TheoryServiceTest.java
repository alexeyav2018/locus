package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.Browser;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.file.FileType;
import ru.locus.file.ImageCompression;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.Role;

/**
 * Правила Теоретического материала: кто их ведёт, что бывает содержимым
 * и что происходит с файлом при заведении, замене и удалении.
 *
 * Все проверки стоят в сервисе, поэтому и проверяются на сервисе, а не через
 * экран: правило должно срабатывать при любом способе вызова, включая
 * контроллер, о котором сейчас никто не думает.
 *
 * Файл достаётся тем же путём, каким его достанет браузер учителя, —
 * обращением по временной подписанной ссылке. Второго пути к содержимому
 * в системе нет, и заводить его ради проверки нельзя: проверка ушла бы
 * проверять не то, что происходит в бою.
 */
class TheoryServiceTest extends IntegrationTest {

    /** Название длиннее колонки: база отвергнет запись — но уже после укладки файла. */
    private static final String TOO_LONG_TITLE = "я".repeat(400);

    @LocalServerPort
    private int port;

    @Autowired
    private TheoryService theory;

    @Autowired
    private TheoryMaterialRepository repository;

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private FileStorage storage;

    @Autowired
    private ImageCompression compression;

    @Autowired
    private TestLibrary library;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Материал с файлом». */
    @Test
    void materialWithAFileIsCreatedAndReadBack() {
        TaxonomyNodeId topic = library.topic();

        TheoryMaterialId id = theory.create("Конспект по производной", topic, pdf(TestLibrary.pdf()));

        TheoryMaterial created = theory.material(id);
        assertThat(created.title()).isEqualTo("Конспект по производной");
        assertThat(created.node()).isEqualTo(topic);
        assertThat(created.hasFile()).isTrue();
        assertThat(created.link()).isNull();
    }

    /** Сценарий «Материал со ссылкой». */
    @Test
    void materialWithALinkIsCreatedAndReadBack() {
        TaxonomyNodeId topic = library.topic();

        TheoryMaterialId id = theory.create("Разбор на видео", topic,
                UploadedContent.ofLink("https://example.org/lecture"));

        TheoryMaterial created = theory.material(id);
        assertThat(created.hasFile()).isFalse();
        assertThat(created.link()).isEqualTo("https://example.org/lecture");
    }

    /** Сценарий «Материал на Разделе»: отказа «только листья» не происходит. */
    @Test
    void materialLiesOnASectionJustAsWellAsOnATopic() {
        TaxonomyNodeId section = library.section();

        TheoryMaterialId id = theory.create("Общий конспект раздела", section, pdf(TestLibrary.pdf()));

        assertThat(theory.material(id).node()).isEqualTo(section);
    }

    /** Сценарии «Ни файла, ни ссылки» и «И файл, и ссылка сразу». */
    @Test
    void contentIsExactlyOneOfTwo() {
        TaxonomyNodeId topic = library.topic();

        assertThatThrownBy(() -> theory.create("Пусто", topic, new UploadedContent(null, null, "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("файл либо ссылка");

        assertThatThrownBy(() -> theory.create("Всё сразу", topic,
                new UploadedContent(TestLibrary.pdf(), FileType.PDF, "https://example.org/lecture")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("содержимое бывает только одно");
    }

    /** Сценарий «Пустое название». */
    @Test
    void materialWithoutATitleIsNotCreated() {
        TaxonomyNodeId topic = library.topic();

        assertThatThrownBy(() -> theory.create("   ", topic, pdf(TestLibrary.pdf())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("название");
    }

    /** Сценарии «Узел не указан» и «Узла не существует». */
    @Test
    void materialNeedsAnExistingNode() {
        assertThatThrownBy(() -> theory.create("Без узла", null, pdf(TestLibrary.pdf())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("узле рубрикатора");

        assertThatThrownBy(() -> theory.create("На несуществующем узле",
                new TaxonomyNodeId(Long.MAX_VALUE), pdf(TestLibrary.pdf())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Узла рубрикатора");
    }

    /**
     * Сценарий «Учитель пытается завести материал»: ведение — дело
     * Администратора, и отказывает в нём сервис, а не разметка экрана.
     */
    @Test
    void teacherIsRefusedEveryEditingOperation() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId existing = library.material(topic, "Уже лежит");
        LoggedIn.as(Role.TEACHER);

        assertThatThrownBy(() -> theory.create("Свой конспект", topic, pdf(TestLibrary.pdf())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> theory.edit(existing, "Переименован", topic))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> theory.replaceContent(existing, UploadedContent.ofLink("https://example.org/")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> theory.delete(existing))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.countByNode(topic)).as("состав материалов остался прежним").isEqualTo(1);
        assertThat(theory.material(existing).title()).isEqualTo("Уже лежит");
    }

    /** Сценарий «Учитель читает материалы»: чтение ролей не спрашивает. */
    @Test
    void teacherReadsTheMaterialsOfANode() {
        TaxonomyNodeId topic = library.topic();
        library.material(topic, "Конспект");
        LoggedIn.as(Role.TEACHER);

        assertThat(theory.materialsOn(topic))
                .extracting(node -> node.material().title())
                .containsExactly("Конспект");
    }

    /** Сценарий «Название изменено». */
    @Test
    void titleIsChanged() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId id = library.material(topic, "Черновик");

        theory.edit(id, "Конспект, вычитанный", topic);

        assertThat(theory.material(id).title()).isEqualTo("Конспект, вычитанный");
    }

    /** Сценарий «Материал перенесён на другой узел». */
    @Test
    void materialIsMovedToAnotherNode() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        TheoryMaterialId id = library.material(from, "Переезжает");

        theory.edit(id, "Переезжает", to);

        assertThat(theory.materialsOn(to)).extracting(node -> node.material().id()).containsExactly(id);
        assertThat(theory.materialsOn(from)).isEmpty();
    }

    /**
     * Задача 4.1: неудача сохранения записи не оставляет в хранилище
     * положенного файла.
     *
     * Сбой настоящий, а не подстроенный подменой репозитория: название длиннее
     * колонки отвергается базой — то есть падает именно сохранение, уже после
     * укладки файла, ровно в том месте, ради которого написана уборка.
     * Хранилище при этом обёрнуто наблюдателем: узнать, что осталось внутри,
     * иначе неоткуда — ключа неудавшегося материала не существует нигде.
     */
    @Test
    void failedSavingLeavesNothingInTheStorage() {
        List<FileKey> stored = new ArrayList<>();
        TheoryService watched = new TheoryService(repository, taxonomy,
                new WatchedStorage(storage, stored), compression);

        assertThatThrownBy(() -> watched.create(TOO_LONG_TITLE, library.topic(), pdf(TestLibrary.pdf())))
                .as("сохранение не удалось")
                .isInstanceOf(RuntimeException.class);

        assertThat(stored).as("положенное убрано: сирот в хранилище не осталось").isEmpty();
    }

    /** Сценарий «Файл заменён»: новый отдаётся, прежний в хранилище не остаётся. */
    @Test
    void replacedFileIsServedWhileTheOldOneIsGone() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId id = theory.create("Конспект", topic, pdf(TestLibrary.pdf()));
        String oldLink = theory.fileLink(id).toString();
        byte[] replacement = TestLibrary.pdf();

        theory.replaceContent(id, pdf(replacement));

        Browser browser = anyone();
        assertThat(browser.getBytes(theory.fileLink(id).toString()))
                .as("материал отдаёт новое содержимое")
                .isEqualTo(replacement);
        assertThat(browser.getBytes(oldLink))
                .as("прежний файл в хранилище не остаётся")
                .isEmpty();
    }

    /** Сценарий «Файл сменился ссылкой»: прежний файл уносится и здесь. */
    @Test
    void fileReplacedByALinkLeavesNoFileBehind() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId id = theory.create("Конспект", topic, pdf(TestLibrary.pdf()));
        String oldLink = theory.fileLink(id).toString();

        theory.replaceContent(id, UploadedContent.ofLink("https://example.org/lecture"));

        assertThat(theory.material(id).link()).isEqualTo("https://example.org/lecture");
        assertThat(theory.material(id).hasFile()).isFalse();
        assertThat(anyone().getBytes(oldLink))
                .as("прежний файл в хранилище не остаётся")
                .isEmpty();
    }

    /** Обратная замена: ссылка сменилась файлом. */
    @Test
    void linkReplacedByAFileBecomesAFile() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId id = theory.create("Разбор", topic, UploadedContent.ofLink("https://example.org/"));
        byte[] file = TestLibrary.pdf();

        theory.replaceContent(id, pdf(file));

        assertThat(theory.material(id).link()).isNull();
        assertThat(anyone().getBytes(theory.fileLink(id).toString())).isEqualTo(file);
    }

    /** Сценарий «Материал удалён»: запись исчезает, файл — тоже. */
    @Test
    void deletedMaterialTakesItsFileOutOfTheStorage() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId id = theory.create("Конспект", topic, pdf(TestLibrary.pdf()));
        String link = theory.fileLink(id).toString();

        theory.delete(id);

        assertThatThrownBy(() -> theory.material(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(theory.materialsOn(topic)).isEmpty();
        assertThat(anyone().getBytes(link))
                .as("файл удалённого материала в хранилище не остаётся")
                .isEmpty();
    }

    /** Материал-ссылка удаляется так же — и хранилище при этом не трогается. */
    @Test
    void deletedLinkMaterialNeedsNoStorage() {
        TaxonomyNodeId topic = library.topic();
        TheoryMaterialId id = library.linkedMaterial(topic, "Разбор на видео");

        assertThatCode(() -> theory.delete(id)).doesNotThrowAnyException();
        assertThat(theory.materialsOn(topic)).isEmpty();
    }

    /**
     * Задача 4.3, сценарий «Файл открывается по выданной ссылке»: наружу
     * файл уходит только временной подписанной ссылкой, и содержимое по ней
     * читается.
     *
     * Сценарий «Ссылка истекла» проверяется тем же рубежом: срок входит
     * в подпись, поэтому отодвинуть его, не сломав подпись, невозможно —
     * а ссылка с чужим сроком отвергается так же, как беззнаковая.
     */
    @Test
    void fileIsServedOnlyBySignedTemporaryLink() {
        byte[] content = TestLibrary.pdf();
        TheoryMaterialId id = theory.create("Конспект", library.topic(), pdf(content));

        String link = theory.fileLink(id).toString();
        String withoutSignature = link.substring(0, link.indexOf('?'));
        String withPostponedExpiry = link.replaceFirst("expires=\\d+", "expires=9999999999");

        Browser browser = anyone();
        assertThat(browser.getBytes(link))
                .as("по действующей ссылке читается ровно то, что положили")
                .isEqualTo(content);
        assertThat(browser.get(withoutSignature).status())
                .as("постоянного адреса у файла материала нет")
                .isEqualTo(403);
        assertThat(browser.get(withPostponedExpiry).status())
                .as("срок входит в подпись: отодвинуть его не удаётся")
                .isEqualTo(403);
    }

    /**
     * Сценарий «Материал-ссылка»: внешний адрес отдаётся как есть, и просить
     * у него подписанную ссылку — ошибка вызывающего.
     */
    @Test
    void aLinkMaterialHasNoFileToSign() {
        TheoryMaterialId id = theory.create("Разбор на видео", library.topic(),
                UploadedContent.ofLink("https://example.org/lecture"));

        assertThatThrownBy(() -> theory.fileLink(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("внешний адрес");
    }

    /**
     * Сценарий «Крупное изображение»: изображение пережимается при загрузке.
     *
     * Ограничения на типы у теории нет — приложить можно и снимок доски,
     * и PDF, — а решение о пережатии принимает сервис, по типу содержимого
     * (standards.md, «Файлы»).
     */
    @Test
    void anImageIsCompressedOnTheWayIn() {
        byte[] photo = image();
        TheoryMaterialId id = theory.create("Снимок доски", library.topic(),
                new UploadedContent(photo, FileType.JPEG, null));

        byte[] stored = anyone().getBytes(theory.fileLink(id).toString());

        assertThat(stored.length)
                .as("снимок пережат: он занимает меньше, чем занимал")
                .isLessThan(photo.length);
    }

    /** А PDF не пережимается никогда: он возвращается тем же, чем был положен. */
    @Test
    void aPdfIsStoredByteForByte() {
        byte[] content = TestLibrary.pdf();
        TheoryMaterialId id = theory.create("Конспект", library.topic(), pdf(content));

        assertThat(anyone().getBytes(theory.fileLink(id).toString())).isEqualTo(content);
    }

    private static UploadedContent pdf(byte[] content) {
        return UploadedContent.ofFile(content, FileType.PDF);
    }

    /** Снимок крупнее предела пережатия — тот же, на котором проверено само пережатие. */
    private static byte[] image() {
        try (InputStream stream = TheoryServiceTest.class.getResourceAsStream("/ru/locus/file/large.jpg")) {
            if (stream == null) {
                throw new IllegalStateException("Нет тестового изображения large.jpg");
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Не прочитать тестовое изображение", e);
        }
    }

    /** Браузер без входа: отдачу файла пропускает подпись, а не сеанс. */
    private Browser anyone() {
        return new Browser(port);
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
