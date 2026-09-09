package ru.locus;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicRepository;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodRepository;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.file.FileType;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyRepository;

/**
 * Обстановка для тестов библиотеки: узлы дерева, записи словарей и Задачи.
 *
 * Заводится через репозитории, а не через сервисы: сервисы требуют прав
 * Администратора и ставят правила, а тесту нужно подготовить обстановку,
 * а не проверить её подготовку. Тем же способом устроен {@link TestAccounts}.
 *
 * Тесты идут на одной базе, поэтому имена здесь неповторяющиеся: имя узла
 * уникально среди братьев, имя записи словаря — на весь словарь.
 */
@Component
public class TestLibrary {

    private final TaxonomyRepository nodes;
    private final SolutionMethodRepository methods;
    private final CharacteristicRepository characteristics;
    private final ProblemRepository problems;
    private final FileStorage storage;

    public TestLibrary(TaxonomyRepository nodes,
                       SolutionMethodRepository methods,
                       CharacteristicRepository characteristics,
                       ProblemRepository problems,
                       FileStorage storage) {
        this.nodes = nodes;
        this.methods = methods;
        this.characteristics = characteristics;
        this.problems = problems;
        this.storage = storage;
    }

    /** Узел без потомков — то есть Тема. */
    public TaxonomyNodeId topic() {
        return nodes.create(unique("Тема"), null);
    }

    /** Узел с потомком — то есть Раздел. */
    public TaxonomyNodeId section() {
        TaxonomyNodeId section = nodes.create(unique("Раздел"), null);
        nodes.create("Потомок", section);
        return section;
    }

    public SolutionMethodId method() {
        return methods.create(unique("Замена переменной"));
    }

    public CharacteristicId characteristic() {
        return characteristics.create(unique("Двойной угол"));
    }

    /** Задача на указанной Теме с одним Методом, без Характеристик. */
    public ProblemId problem(TaxonomyNodeId topic) {
        return problem(topic, method());
    }

    public ProblemId problem(TaxonomyNodeId topic, SolutionMethodId method) {
        return problems.create(null, ExamPart.SECOND, storedPdf(), storedPdf(),
                List.of(topic), List.of(method), List.of());
    }

    /** Файл, уже лежащий в хранилище, — как если бы его положил сервис. */
    public FileKey storedPdf() {
        return storage.put(pdf(), FileType.PDF);
    }

    /**
     * Содержимое «PDF»: заголовок настоящего PDF и неповторяющийся хвост.
     * Разбирать его никто не будет — система формул не читает, — но байты
     * должны отличаться, чтобы «отдан тот самый файл» что-то проверяло.
     */
    public static byte[] pdf() {
        return ("%PDF-1.4 " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
