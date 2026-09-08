package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Ключ файла: отдельный тип, случайное значение, расширение по типу содержимого.
 *
 * Проверяется требование «Файл кладётся в хранилище и адресуется ключом»:
 * ключ выдаёт хранилище, два файла с одинаковым исходным именем получают
 * разные ключи, а разделитель пути в значении невозможен — иначе запись ушла бы
 * за пределы каталога хранилища.
 */
class FileKeyTest {

    @Test
    void emptyValueIsRejected() {
        assertThatThrownBy(() -> new FileKey(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не может быть пустым");

        assertThatThrownBy(() -> new FileKey("   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new FileKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathSeparatorIsRejected() {
        assertThatThrownBy(() -> new FileKey("problems/12.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("разделитель пути");

        assertThatThrownBy(() -> new FileKey("problems\\12.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("разделитель пути");
    }

    @Test
    void goingUpIsRejected() {
        assertThatThrownBy(() -> new FileKey("..secret.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("переход вверх");
    }

    @Test
    void generatedKeysAreDifferentForTheSameContentType() {
        FileKey first = FileKey.generated(FileType.PDF);
        FileKey second = FileKey.generated(FileType.PDF);

        assertThat(first).isNotEqualTo(second);
        assertThat(first.extension()).isEqualTo("pdf");
        assertThat(second.extension()).isEqualTo("pdf");
    }

    @Test
    void extensionFollowsContentType() {
        assertThat(FileKey.generated(FileType.JPEG).extension()).isEqualTo("jpg");
        assertThat(FileKey.generated(FileType.PNG).extension()).isEqualTo("png");
        assertThat(FileKey.generated("image/jpeg; charset=binary").extension()).isEqualTo("jpg");
        assertThat(FileKey.generated("невнятное/что-то").extension()).isEqualTo("bin");
    }

    @Test
    void contentTypeIsRestoredFromTheKey() {
        assertThat(FileType.contentTypeFor(FileKey.generated(FileType.PDF))).isEqualTo(FileType.PDF);
        assertThat(FileType.contentTypeFor(FileKey.generated(FileType.JPEG))).isEqualTo(FileType.JPEG);
        assertThat(FileType.contentTypeFor(FileKey.generated(FileType.PNG))).isEqualTo(FileType.PNG);
        assertThat(FileType.contentTypeFor(FileKey.generated("невнятное/что-то"))).isEqualTo(FileType.BINARY);
    }
}
