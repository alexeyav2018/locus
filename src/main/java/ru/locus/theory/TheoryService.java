package ru.locus.theory;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.file.FileType;
import ru.locus.file.ImageCompression;
import ru.locus.taxonomy.TaxonomyNode;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;

/**
 * Ведение Теоретических материалов: правила заведения, правки и удаления,
 * а также показ материалов выбранного узла вместе с унаследованными.
 *
 * Права проверяются здесь, а не в контроллере и не на адресах (standards.md,
 * «Слои и границы»): правило привязано к операции и срабатывает при любом
 * способе вызова. Ведёт материалы только Администратор; читают их все
 * вошедшие — вход уже потребован грубым рубежом в {@code SecurityConfig},
 * а роли для чтения общей библиотеки не важны.
 *
 * Теория — общая библиотека, и по владельцу она <b>не фильтруется</b>:
 * параметра «чей» нет ни здесь, ни в репозитории (ADR-0027). Сторожит это
 * отдельный тест изоляции — {@code OwnerIsUnknownToTheoryTest}.
 *
 * Заморозки «пока не использован», действующей для Задачи (ADR-0030), у теории
 * нет: материал правится и удаляется в любой момент (ADR-0033). Разметки,
 * влияющей на измерение владения, у материала не бывает, и его правка ничего
 * не пересчитывает задним числом.
 *
 * Файлы кладутся и отдаются только через {@link FileStorage}: SDK хранилища
 * здесь не появляется, а наружу файл уходит исключительно временной
 * подписанной ссылкой (standards.md, «Файлы»).
 */
@Service
public class TheoryService {

    private final TheoryMaterialRepository materials;
    private final TaxonomyService taxonomy;
    private final FileStorage storage;
    private final ImageCompression compression;

    public TheoryService(TheoryMaterialRepository materials,
                         TaxonomyService taxonomy,
                         FileStorage storage,
                         ImageCompression compression) {
        this.materials = materials;
        this.taxonomy = taxonomy;
        this.storage = storage;
        this.compression = compression;
    }

    public TheoryMaterial material(TheoryMaterialId id) {
        return existing(id);
    }

    /**
     * Материалы выбранного узла: свои и унаследованные от всех предков
     * до корня (ADR-0032).
     *
     * <p>Наследование <b>вычисляется</b>, а не хранится: узлы берутся подъёмом
     * по предкам ({@link TaxonomyService#ancestry}), материалы всего набора —
     * одним запросом. Своего подъёма здесь не заводится по той же причине,
     * по которой поиск Задач не заводит своего спуска: две копии обхода
     * расходятся молча, и потерянные узлы выглядят как честное «материалов
     * нет» (standards.md, «Данные»). Оттуда же берётся отказ на несуществующем
     * узле.
     *
     * <p>Порядок задаётся здесь, а не запросом: сначала свои материалы узла,
     * дальше — от ближайшего предка к корню, потому что чем дальше узел, тем
     * общее материал. Внутри одного узла порядок приходит из репозитория —
     * по названию. Запрос такого порядка не знает: он получает набор узлов,
     * а не цепочку.
     *
     * <p>Наследование идёт только вниз: материалы потомков в список
     * не попадают — узкий материал одного листа к соседним листьям
     * не относится.
     *
     * <p>Без {@code @PreAuthorize} — как и остальное чтение общей библиотеки:
     * вход уже потребован грубым рубежом {@code SecurityConfig}, а роль
     * Администратора закрыла бы теорию от Учителя, то есть от того, ради кого
     * она ведётся.
     */
    public List<NodeTheory> materialsOn(TaxonomyNodeId node) {
        List<TaxonomyNode> chain = taxonomy.ancestry(node);
        Map<TaxonomyNodeId, List<TheoryMaterial>> byNode = new LinkedHashMap<>();
        for (TheoryMaterial material : materials.findByNodes(chain.stream().map(TaxonomyNode::id).toList())) {
            byNode.computeIfAbsent(material.node(), any -> new ArrayList<>()).add(material);
        }

        List<NodeTheory> found = new ArrayList<>();
        // Цепочка приходит от корня, а показывается с конца: свой узел первым,
        // дальше предки от ближайшего к корню.
        for (TaxonomyNode source : chain.reversed()) {
            boolean own = source.id().equals(node);
            for (TheoryMaterial material : byNode.getOrDefault(source.id(), List.of())) {
                found.add(new NodeTheory(material, source.name(), own));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Временная подписанная ссылка на приложенный файл материала.
     *
     * Ключ файла наружу не отдаётся: постоянного адреса у файла нет
     * (ADR-0021, antipatterns.md, «Публичный бакет или постоянная ссылка»).
     * У материала-ссылки файла нет вовсе, и спрашивать её здесь — ошибка
     * вызывающего: внешний адрес отдаётся как есть, подписывать чужой адрес
     * нечем и незачем.
     */
    public URI fileLink(TheoryMaterialId id) {
        TheoryMaterial material = existing(id);
        if (!material.hasFile()) {
            throw new IllegalArgumentException("У материала «" + material.title()
                    + "» вместо файла внешний адрес: он отдаётся как есть");
        }
        return storage.temporaryLink(material.file());
    }

    /**
     * Заводит материал на узле.
     *
     * <p>Узел — любой: и Раздел, и Тема. Правило «смысл только в листьях»,
     * которым связаны Задачи и отметки Владения, на теорию не действует
     * (ADR-0032), поэтому вид узла здесь не спрашивается — спрашивается только
     * его существование.
     *
     * <p>Порядок работы с хранилищем тот же, что у Задачи: сначала файл, затем
     * запись, а при неудаче сохранения — уборка положенного. Общей транзакции
     * у базы и хранилища нет и быть не может, поэтому выбирается меньшее
     * из зол: забытый файл не адресуется ниоткуда, а материал без файла был бы
     * виден в списке и выглядел настоящим (standards.md, «Файлы»).
     *
     * <p>Проверки идут до укладки: отказ не должен оставлять в хранилище
     * ничего.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public TheoryMaterialId create(String title, TaxonomyNodeId node, UploadedContent content) {
        String checkedTitle = TheoryMaterial.requiredTitle(title);
        requireExistingNode(node);
        String link = checkedContent(content);

        FileKey file = stored(content);
        try {
            return materials.create(checkedTitle, node, file, link);
        } catch (RuntimeException failure) {
            discard(file);
            throw failure;
        }
    }

    /**
     * Меняет название материала и узел, на котором он лежит.
     *
     * Перенос на другой узел меняет и наследование — само собой, потому что
     * оно вычисляется: на прежнем поддереве материал больше не виден,
     * на новом виден. Пересчитывать нечего.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void edit(TheoryMaterialId id, String title, TaxonomyNodeId node) {
        TheoryMaterial material = existing(id);
        String checkedTitle = TheoryMaterial.requiredTitle(title);
        requireExistingNode(node);

        materials.changeTitle(material.id(), checkedTitle);
        materials.changeNode(material.id(), node);
    }

    /**
     * Заменяет содержимое: файл на другой файл, файл на ссылку, ссылку
     * на файл — это одна и та же правка.
     *
     * Порядок важен: положить новый, переписать запись, удалить прежний.
     * Удаление прежнего идёт последним — потерять новый файл хуже, чем
     * оставить старый. Прежний файл уносится и тогда, когда содержимым
     * стала ссылка: адресоваться к нему больше неоткуда.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void replaceContent(TheoryMaterialId id, UploadedContent content) {
        TheoryMaterial material = existing(id);
        String link = checkedContent(content);

        FileKey replacement = stored(content);
        materials.replaceContent(material.id(), replacement, link);
        if (material.hasFile()) {
            storage.delete(material.file());
        }
    }

    /**
     * Снимает материал вместе с приложенным файлом — в любой момент,
     * без условия «пока не использован» (ADR-0033).
     *
     * Файл уносится потому, что кроме кода теории распознать ненужный файл
     * некому: хранилище не знает, что лежит по ключу, а оставленный файл уже
     * ничем не адресуется — отличить его от нужного впоследствии невозможно.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void delete(TheoryMaterialId id) {
        TheoryMaterial material = existing(id);

        materials.delete(material.id());
        if (material.hasFile()) {
            storage.delete(material.file());
        }
    }

    /**
     * Проверка присланного содержимого до всякой укладки.
     *
     * Правило «ровно одно из двух» здесь не переписывается: спрашивается
     * та же {@link TheoryMaterial#requireContent}, которой пользуется
     * конструктор записи. Вторая копия правила разошлась бы с первой молча —
     * и материал сохранялся бы, а обратно не читался.
     *
     * @return приведённая ссылка либо {@code null}, если содержимое — файл
     */
    private static String checkedContent(UploadedContent content) {
        if (content == null) {
            throw new IllegalArgumentException("Теоретическому материалу нужен файл либо ссылка");
        }
        String link = TheoryMaterial.normalizedLink(content.link());
        TheoryMaterial.requireContent(content.hasFile(), link);
        return link;
    }

    /**
     * Кладёт файл в хранилище, если он приложен, и возвращает ключ.
     *
     * Изображение пережимается, всё прочее — нет: ограничения на типы
     * у теории нет (ADR-0018 говорит «файл», а не «PDF»), а решает
     * о пережатии вызывающий, по типу содержимого — само хранилище
     * о том, что ему дали, не знает (standards.md, «Файлы»).
     */
    private FileKey stored(UploadedContent content) {
        if (!content.hasFile()) {
            return null;
        }
        if (FileType.isImage(content.contentType())) {
            ImageCompression.Compressed compressed = compression.compress(content.file());
            return storage.put(compressed.content(), compressed.contentType());
        }
        return storage.put(content.file(), content.contentType());
    }

    /**
     * Уборка файла, положенного в хранилище перед неудавшейся записью.
     *
     * Сбой самой уборки не должен подменять собой исходную ошибку: тот, кто
     * заводил материал, должен увидеть, почему тот не завёлся, а не почему
     * не удалось убрать за собой. Оставшийся файл при этом никому не мешает —
     * он не адресуется ниоткуда.
     */
    private void discard(FileKey key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Исходная ошибка важнее; см. пояснение выше.
        }
    }

    /**
     * Узел обязателен и должен существовать.
     *
     * Вид узла не проверяется намеренно: материал ложится и на Раздел
     * (ADR-0032). Существование спрашивается у дерева — {@link
     * TaxonomyService#node} отказывает на отсутствующем узле, и внешний ключ
     * остаётся вторым рубежом, а не единственным: исключение драйвера,
     * всплывшее из репозитория, ничего не объясняет человеку.
     */
    private void requireExistingNode(TaxonomyNodeId node) {
        if (node == null) {
            throw new IllegalArgumentException("Теоретический материал должен лежать на узле рубрикатора");
        }
        taxonomy.node(node);
    }

    private TheoryMaterial existing(TheoryMaterialId id) {
        return materials.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "Теоретического материала № " + id.value() + " не существует"));
    }
}
