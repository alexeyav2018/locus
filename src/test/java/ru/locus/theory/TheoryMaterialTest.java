package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Требование «Материал — название, узел и содержимое: файл либо ссылка».
 *
 * Правила проверяются на самой записи, а не на сервисе: запись, собранная
 * в обход правил, не должна существовать ни в памяти, ни в базе. Пять отказов
 * здесь — это пять сценариев требования: пустое название, отсутствие узла,
 * пустое содержимое, двойное содержимое и ссылка с чужой схемой.
 */
class TheoryMaterialTest {

    private static final TheoryMaterialId ID = new TheoryMaterialId(1);
    private static final TaxonomyNodeId NODE = new TaxonomyNodeId(7);
    private static final FileKey FILE = new FileKey("f47ac10b.pdf");

    /** Сценарий «Материал с файлом». */
    @Test
    void materialWithAFileIsLegal() {
        TheoryMaterial material = new TheoryMaterial(ID, "Конспект по тригонометрии", NODE, FILE, null);

        assertThat(material.hasFile()).isTrue();
        assertThat(material.file()).isEqualTo(FILE);
        assertThat(material.link()).isNull();
    }

    /** Сценарий «Материал со ссылкой». */
    @Test
    void materialWithALinkIsLegal() {
        TheoryMaterial material =
                new TheoryMaterial(ID, "Разбор на видео", NODE, null, "https://example.org/lecture");

        assertThat(material.hasFile()).isFalse();
        assertThat(material.link()).isEqualTo("https://example.org/lecture");
    }

    /** Сценарий «Ни файла, ни ссылки». */
    @Test
    void materialWithoutAnyContentDoesNotExist() {
        assertThatThrownBy(() -> new TheoryMaterial(ID, "Пустышка", NODE, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("файл либо ссылка");
    }

    /** Сценарий «И файл, и ссылка сразу». */
    @Test
    void materialWithBothKindsOfContentDoesNotExist() {
        assertThatThrownBy(() -> new TheoryMaterial(ID, "И то и другое", NODE, FILE, "https://example.org"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("только одно");
    }

    /** Сценарий «Пустое название». */
    @Test
    void titleIsRequired() {
        assertThatThrownBy(() -> new TheoryMaterial(ID, "   ", NODE, FILE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("название");
        assertThatThrownBy(() -> new TheoryMaterial(ID, null, NODE, FILE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void titleLosesTheSpacesAroundIt() {
        assertThat(new TheoryMaterial(ID, "  Конспект  ", NODE, FILE, null).title()).isEqualTo("Конспект");
    }

    /** Сценарий «Узел не указан»: без узла материал негде показать. */
    @Test
    void nodeIsRequired() {
        assertThatThrownBy(() -> new TheoryMaterial(ID, "Конспект", null, FILE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("узле");
    }

    /**
     * Ссылка с чужой схемой отвергается: {@code javascript:} в атрибуте
     * {@code href} — исполняемый код в чужом браузере, и приехал бы он туда
     * через общую библиотеку, то есть ко всем Учителям сразу.
     */
    @Test
    void linkWithAnotherSchemeIsRefused() {
        assertThatThrownBy(() -> new TheoryMaterial(ID, "Ловушка", NODE, null, "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> new TheoryMaterial(ID, "Файл на диске", NODE, null, "file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Ссылка из одних пробелов — это отсутствие ссылки, а не ссылка: иначе
     * материал с приложенным файлом и пустым полем адреса выглядел бы
     * материалом с двумя содержимыми сразу.
     */
    @Test
    void blankLinkIsNoLinkAtAll() {
        TheoryMaterial material = new TheoryMaterial(ID, "Конспект", NODE, FILE, "   ");

        assertThat(material.link()).isNull();
        assertThat(material.hasFile()).isTrue();
    }

    @Test
    void identifierIsRequired() {
        assertThatThrownBy(() -> new TheoryMaterial(null, "Конспект", NODE, FILE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("идентификатор");
    }
}
