package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;
import ru.locus.TestLibrary;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Хранение Теоретических материалов: материал читается обратно таким, каким
 * записан, материалы набора узлов приходят одним списком, а содержимое
 * остаётся ровно одним из двух.
 *
 * Проверка идёт на настоящей базе в контейнере — подмены репозитория нет:
 * половина проверяемого здесь живёт в SQL, во внешнем ключе и в проверочном
 * ограничении, а не в Java. Именно поэтому тесты на «оба содержимого сразу»
 * и «ни одного» зовут репозиторий напрямую, в обход записи
 * {@link TheoryMaterial}: они спрашивают схему, а не конструктор.
 *
 * Тесты идут на одной базе, поэтому обстановку каждый заводит себе сам
 * ({@link TestLibrary}).
 */
class TheoryMaterialRepositoryTest extends IntegrationTest {

    @Autowired
    private TheoryMaterialRepository materials;

    @Autowired
    private TestLibrary library;

    @Test
    void createdMaterialIsReadBackAsItWasWritten() {
        TaxonomyNodeId node = library.topic();
        FileKey file = library.storedPdf();

        TheoryMaterialId id = materials.create("Конспект по тригонометрии", node, file, null);

        TheoryMaterial found = materials.findById(id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.title()).isEqualTo("Конспект по тригонометрии");
        assertThat(found.node()).isEqualTo(node);
        assertThat(found.file()).isEqualTo(file);
        assertThat(found.link()).isNull();
    }

    @Test
    void materialWithALinkIsReadBackAsALink() {
        TaxonomyNodeId node = library.topic();

        TheoryMaterialId id = materials.create("Разбор на видео", node, null, "https://example.org/lecture");

        TheoryMaterial found = materials.findById(id).orElseThrow();
        assertThat(found.hasFile()).isFalse();
        assertThat(found.link()).isEqualTo("https://example.org/lecture");
    }

    /**
     * Материал ложится и на Раздел тоже: правило «только листья» на теорию
     * не распространяется (ADR-0032), и ограничения на вид узла в схеме нет —
     * вида узла схема вообще не хранит.
     */
    @Test
    void materialLiesOnASectionJustAsWellAsOnATopic() {
        TaxonomyNodeId section = library.section();

        TheoryMaterialId id = materials.create("Общий конспект раздела", section, library.storedPdf(), null);

        assertThat(materials.findById(id)).isPresent();
    }

    /**
     * Набор узлов приходит от подъёма по предкам; запрос отдаёт материалы
     * всех перечисленных узлов сразу, по названию без учёта регистра.
     * Раскладку по узлам делает сервис — здесь только общий список.
     */
    @Test
    void materialsOfSeveralNodesComeInOneListOrderedByTitle() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        materials.create("яблоко", topic, null, "https://example.org/1");
        materials.create("Арбуз", section, null, "https://example.org/2");
        materials.create("багульник", section, null, "https://example.org/3");

        List<TheoryMaterial> found = materials.findByNodes(List.of(section, topic));

        assertThat(found).extracting(TheoryMaterial::title)
                .as("порядок по названию без учёта регистра, узлы вперемешку")
                .containsExactly("Арбуз", "багульник", "яблоко");
    }

    @Test
    void materialsOfAnotherBranchDoNotComeAlong() {
        TaxonomyNodeId node = library.topic();
        TaxonomyNodeId neighbour = library.topic();
        materials.create("Свой", node, null, "https://example.org/own");
        materials.create("Чужой", neighbour, null, "https://example.org/alien");

        assertThat(materials.findByNodes(List.of(node))).extracting(TheoryMaterial::title)
                .containsExactly("Свой");
    }

    @Test
    void nodeWithoutMaterialsGivesAnEmptyList() {
        assertThat(materials.findByNodes(List.of(library.topic()))).isEmpty();
    }

    /**
     * Пустой набор узлов — испорченный вызов, а не запрос «ничего». Подъём
     * по предкам всегда содержит сам узел, и пустой ответ на такой вызов
     * выглядел бы правдоподобно — то есть скрыл бы ошибку.
     */
    @Test
    void emptySetOfNodesIsARefusalRatherThanAnEmptyAnswer() {
        assertThatThrownBy(() -> materials.findByNodes(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("испорчен");
    }

    /** Счёт — только своих материалов узла: он отвечает на вопросы дерева. */
    @Test
    void countAnswersHowManyMaterialsLieOnTheNodeItself() {
        TaxonomyNodeId section = library.topic();
        TaxonomyNodeId topic = library.topic(section);
        materials.create("На разделе", section, null, "https://example.org/1");
        materials.create("На теме", topic, null, "https://example.org/2");
        materials.create("И ещё на теме", topic, null, "https://example.org/3");

        assertThat(materials.countByNode(section)).isEqualTo(1);
        assertThat(materials.countByNode(topic)).isEqualTo(2);
        assertThat(materials.countByNode(library.topic())).isZero();
    }

    @Test
    void titleAndNodeAreChanged() {
        TaxonomyNodeId node = library.topic();
        TaxonomyNodeId another = library.topic();
        TheoryMaterialId id = materials.create("Как было", node, library.storedPdf(), null);

        materials.changeTitle(id, "Как стало");
        materials.changeNode(id, another);

        TheoryMaterial found = materials.findById(id).orElseThrow();
        assertThat(found.title()).isEqualTo("Как стало");
        assertThat(found.node()).isEqualTo(another);
    }

    /**
     * Замена содержимого меняет обе колонки разом — иначе промежуточное
     * состояние «и файл, и ссылка» не прошло бы проверочное ограничение.
     */
    @Test
    void contentIsReplacedWholeInBothDirections() {
        TaxonomyNodeId node = library.topic();
        TheoryMaterialId id = materials.create("Материал", node, library.storedPdf(), null);

        materials.replaceContent(id, null, "https://example.org/instead");

        TheoryMaterial asLink = materials.findById(id).orElseThrow();
        assertThat(asLink.file()).isNull();
        assertThat(asLink.link()).isEqualTo("https://example.org/instead");

        FileKey file = library.storedPdf();
        materials.replaceContent(id, file, null);

        TheoryMaterial asFile = materials.findById(id).orElseThrow();
        assertThat(asFile.file()).isEqualTo(file);
        assertThat(asFile.link()).isNull();
    }

    @Test
    void deletedMaterialIsGoneAndLeavesTheNeighbourAlone() {
        TaxonomyNodeId node = library.topic();
        TheoryMaterialId doomed = materials.create("Уйдёт", node, null, "https://example.org/1");
        TheoryMaterialId kept = materials.create("Останется", node, null, "https://example.org/2");

        materials.delete(doomed);

        assertThat(materials.findById(doomed)).isEmpty();
        assertThat(materials.findById(kept)).isPresent();
    }

    @Test
    void unknownMaterialIsSimplyAbsent() {
        assertThat(materials.findById(new TheoryMaterialId(999_999))).isEmpty();
    }

    /** Внешний ключ — второй рубеж: узла нет, и материал на него не ложится. */
    @Test
    void referenceToANodeThatDoesNotExistIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> materials.create("Висящий", new TaxonomyNodeId(999_999), null,
                "https://example.org/nowhere"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Проверочное ограничение схемы держит «ровно одно из двух» и тогда, когда
     * в таблицу пишут мимо записи {@link TheoryMaterial}: инвариант, выразимый
     * схемой, выражен схемой (standards.md, «Данные»).
     */
    @Test
    void schemaRefusesBothKindsOfContentAtOnce() {
        TaxonomyNodeId node = library.topic();

        assertThatThrownBy(() -> materials.create("И то и другое", node, library.storedPdf(),
                "https://example.org/both"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaRefusesAMaterialWithoutAnyContent() {
        TaxonomyNodeId node = library.topic();

        assertThatThrownBy(() -> materials.create("Пустышка", node, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
