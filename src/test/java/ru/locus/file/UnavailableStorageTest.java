package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import ru.locus.Browser;
import ru.locus.IntegrationTest;

/**
 * Недоступность хранилища не останавливает систему.
 *
 * Хранилище — единственная внешняя зависимость (architecture.md, «Внешние
 * зависимости»). Её отказ обязан выглядеть отказом файлов, а не отказом
 * системы: страницы, файлов не касающиеся, продолжают открываться, а причина
 * попадает в журнал — по ней и разбираются.
 *
 * Хранилище здесь настроено на закрытый порт: соединиться не с чем, и это
 * и есть проверяемое обстоятельство.
 */
@TestPropertySource(properties = {
        "locus.file.storage=object",
        "locus.file.object.endpoint=http://localhost:1",
        "locus.file.object.region=ru-central1",
        "locus.file.object.bucket=locus-unreachable",
        "locus.file.object.access-key=ключ",
        "locus.file.object.secret-key=секрет"
})
class UnavailableStorageTest extends IntegrationTest {

    @Autowired
    private FileStorage storage;

    @LocalServerPort
    private int port;

    private final ListAppender<ILoggingEvent> journal = new ListAppender<>();

    @BeforeEach
    void listenToTheJournal() {
        journal.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FileStorageUnavailableException.class))
                .addAppender(journal);
    }

    @AfterEach
    void stopListening() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FileStorageUnavailableException.class))
                .detachAppender(journal);
        journal.stop();
    }

    @Test
    void storingFailsExplicitlyAndTheReasonReachesTheJournal() {
        assertThatThrownBy(() -> storage.put("что-нибудь".getBytes(StandardCharsets.UTF_8), FileType.PDF))
                .isInstanceOf(FileStorageUnavailableException.class);

        assertThat(journal.list)
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("locus-unreachable"));
    }

    @Test
    void unavailableStorageDoesNotLookLikeAMissingFile() {
        assertThatThrownBy(() -> storage.put("что-нибудь".getBytes(StandardCharsets.UTF_8), FileType.PDF))
                .isInstanceOf(FileStorageUnavailableException.class)
                .hasMessageContaining("Не положить объект");
    }

    @Test
    void pagesThatDoNotTouchFilesKeepWorking() {
        Browser.Page form = new Browser(port).follow("/");

        assertThat(form.status()).isEqualTo(200);
        assertThat(form.body()).contains("<h1>Вход</h1>");
    }
}
