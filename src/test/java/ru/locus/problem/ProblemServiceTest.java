package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Правила Задачи: разметка только листьями, существование указанных ссылок,
 * правка и удаление.
 *
 * Все проверки стоят в сервисе, поэтому и проверяются на сервисе, а не через
 * экран: правило должно срабатывать при любом способе вызова, включая
 * контроллер, о котором сейчас никто не думает.
 */
class ProblemServiceTest extends IntegrationTest {

    @Autowired
    private ProblemService problems;

    @Autowired
    private ProblemRepository repository;

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

    /** Сценарий «Задача заведена». */
    @Test
    void problemAppearsInTheLibraryWithBothFilesAndItsMarkup() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();

        ProblemId id = problems.create("Ященко, вариант 12", ExamPart.SECOND,
                List.of(topic), List.of(method), List.of(), pdf(), pdf());

        Problem created = problems.problem(id);
        assertThat(created.caption()).isEqualTo("Ященко, вариант 12");
        assertThat(created.topics()).containsExactly(topic);
        assertThat(created.methods()).containsExactly(method);
        assertThat(problems.problemsOf(topic)).extracting(Problem::id).contains(id);
    }

    /**
     * Сценарий «Попытка привязать Задачу к Разделу» — тест на инвариант 1.
     *
     * Внешним ключом это не выражается: отдельной таблицы Тем нет, вид узла
     * не хранится. Значит проверка живёт в сервисе — и падать должна она,
     * а не база.
     */
    @Test
    void problemCannotBeAttachedToASection() {
        TaxonomyNodeId section = library.section();

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(section), List.of(library.method()), List.of(), pdf(), pdf()))
                .isInstanceOf(NotATopicException.class)
                .hasMessageContaining("Задачи несут только Темы");

        assertThat(repository.countByTopic(section)).as("Задача не завелась").isZero();
    }

    /** Сценарий «Задача без Темы». */
    @Test
    void problemWithoutATopicIsNotCreated() {
        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(), List.of(library.method()), List.of(), pdf(), pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна Тема");
    }

    /** Сценарий «Задача без Метода». */
    @Test
    void problemWithoutAMethodIsNotCreated() {
        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(library.topic()), List.of(), List.of(), pdf(), pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы один Метод");
    }

    /** Сценарий «Часть указывается ровно одна» — не указанная отклоняется. */
    @Test
    void problemWithoutAnExamPartIsNotCreated() {
        assertThatThrownBy(() -> problems.create(null, null,
                List.of(library.topic()), List.of(library.method()), List.of(), pdf(), pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Часть");
    }

    /** Сценарий «Нет PDF решения»: отказ называет недостающий файл. */
    @Test
    void missingFileIsRefusedAndNamed() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(topic), List.of(method), List.of(), pdf(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("решения");

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(topic), List.of(method), List.of(), null, pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("условия");
    }

    /** Задача 3.3: отсутствующая Тема, Метод или Характеристика — внятный отказ. */
    @Test
    void referenceToSomethingThatDoesNotExistIsRefusedWithAnExplanation() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(new TaxonomyNodeId(Long.MAX_VALUE)), List.of(method), List.of(), pdf(), pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Узла рубрикатора");

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(topic), List.of(new SolutionMethodId(Long.MAX_VALUE)), List.of(), pdf(), pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Метода");

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                List.of(topic), List.of(method), List.of(new CharacteristicId(Long.MAX_VALUE)), pdf(), pdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Характеристики");
    }

    /** Сценарий «Разметка изменена». */
    @Test
    void markupIsChangedAndTheNumberStays() {
        TaxonomyNodeId topic = library.topic();
        ProblemId id = library.problem(topic);
        SolutionMethodId added = library.method();
        List<SolutionMethodId> both = List.of(problems.problem(id).methods().get(0), added);

        problems.edit(id, "Подпись появилась", ExamPart.FIRST, List.of(topic), both, List.of());

        Problem edited = problems.problem(id);
        assertThat(edited.methods()).containsExactlyInAnyOrderElementsOf(both);
        assertThat(edited.caption()).isEqualTo("Подпись появилась");
        assertThat(edited.number()).as("номер при правке не меняется").isEqualTo(id.value());
    }

    /** Сценарий «Правка не снимает обязательности». */
    @Test
    void editingCannotRemoveTheLastMethod() {
        TaxonomyNodeId topic = library.topic();
        ProblemId id = library.problem(topic);
        List<SolutionMethodId> before = problems.problem(id).methods();

        assertThatThrownBy(() -> problems.edit(id, null, ExamPart.FIRST, List.of(topic), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(problems.problem(id).methods())
                .as("разметка Задачи осталась прежней")
                .isEqualTo(before);
    }

    /** Правка тоже не пускает Задачу на Раздел. */
    @Test
    void editingCannotMoveTheProblemOntoASection() {
        ProblemId id = library.problem(library.topic());
        TaxonomyNodeId section = library.section();

        assertThatThrownBy(() -> problems.edit(id, null, ExamPart.FIRST,
                List.of(section), problems.problem(id).methods(), List.of()))
                .isInstanceOf(NotATopicException.class);
    }

    /** Сценарий «Задача удалена» и «Разметка соседних Задач не задета». */
    @Test
    void deletedProblemLeavesTheNeighboursAlone() {
        TaxonomyNodeId topic = library.topic();
        ProblemId doomed = library.problem(topic);
        ProblemId neighbour = library.problem(topic);
        List<SolutionMethodId> neighbourMethods = problems.problem(neighbour).methods();

        problems.delete(doomed);

        assertThat(problems.problemsOf(topic)).extracting(Problem::id).containsExactly(neighbour);
        assertThat(problems.problem(neighbour).methods()).isEqualTo(neighbourMethods);
        assertThatThrownBy(() -> problems.problem(doomed)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Задача 6.1: правка и удаление проходят, потому что реализаций вопроса
     * «использована ли Задача» сегодня нет ни одной — ни Заданий, ни Работ
     * в системе не существует, и условие истинно тождественно (ADR-0030).
     *
     * Тест закрепляет именно это состояние: появится первая реализация —
     * поведение изменится осознанно, а не обнаружится случайно.
     */
    @Test
    void withoutAssignmentsAndSubmissionsNothingBlocksEditingOrDeleting() {
        TaxonomyNodeId topic = library.topic();
        ProblemId id = library.problem(topic);

        assertThatCode(() -> problems.edit(id, "Правится", ExamPart.FIRST,
                List.of(topic), problems.problem(id).methods(), List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> problems.delete(id)).doesNotThrowAnyException();
    }

    /**
     * Перестройка дерева ({@code rubricator-restructure}), углубление:
     * все Задачи Темы переезжают на приёмник, прочая разметка на месте.
     */
    @Test
    void rehomingMovesEveryProblemOfTheTopicToTheReceiver() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        ProblemId first = library.problem(from);
        ProblemId second = library.problem(from);
        List<SolutionMethodId> firstMethods = problems.problem(first).methods();

        problems.rehomeTopic(from, to);

        assertThat(problems.problemsOf(to)).extracting(Problem::id).containsExactly(first, second);
        assertThat(problems.problemsOf(from)).isEmpty();
        assertThat(problems.problem(first).methods()).as("Методы не задеты").isEqualTo(firstMethods);
    }

    /** Приёмника, которого нет, переезд не принимает — и внятно, а не ошибкой ключа. */
    @Test
    void rehomingOntoAMissingNodeIsRefusedAndNothingMoves() {
        TaxonomyNodeId from = library.topic();
        ProblemId id = library.problem(from);

        assertThatThrownBy(() -> problems.rehomeTopic(from, new TaxonomyNodeId(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(problems.problem(id).topics()).containsExactly(from);
    }

    /**
     * Перестройка, снятие Темы: каждая Задача уезжает на свой приёмник
     * (ADR-0007, «распределяются поштучно»).
     */
    @Test
    void distributionSendsEachProblemToItsOwnReceiver() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId left = library.topic();
        TaxonomyNodeId right = library.topic();
        ProblemId first = library.problem(from);
        ProblemId second = library.problem(from);
        ProblemId third = library.problem(from);

        problems.distribute(from, Map.of(first, left, second, right, third, left));

        assertThat(problems.problemsOf(left)).extracting(Problem::id).containsExactly(first, third);
        assertThat(problems.problemsOf(right)).extracting(Problem::id).containsExactly(second);
        assertThat(problems.problemsOf(from)).isEmpty();
    }

    /** Пропущенная Задача — отказ целиком: ни одна из названных не уехала. */
    @Test
    void incompleteDistributionIsRefusedBeforeAnythingMoves() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        ProblemId named = library.problem(from);
        ProblemId forgotten = library.problem(from);

        assertThatThrownBy(() -> problems.distribute(from, Map.of(named, to)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("неполно")
                .hasMessageContaining(String.valueOf(forgotten.value()));

        assertThat(problems.problemsOf(from)).extracting(Problem::id).containsExactly(named, forgotten);
        assertThat(problems.problemsOf(to)).isEmpty();
    }

    /** Посторонняя Задача в карте — тоже отказ целиком. */
    @Test
    void distributionNamingAStrangerIsRefused() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        ProblemId own = library.problem(from);
        ProblemId stranger = library.problem(library.topic());

        assertThatThrownBy(() -> problems.distribute(from, Map.of(own, to, stranger, to)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не размеченные")
                .hasMessageContaining(String.valueOf(stranger.value()));

        assertThat(problems.problemsOf(from)).extracting(Problem::id).containsExactly(own);
        assertThat(problems.problemsOf(to)).isEmpty();
    }

    /**
     * Перестройка — дело Администратора, как и всё ведение библиотеки;
     * отказывает сервис, а не экран.
     */
    @Test
    void teacherIsRefusedBothRestructuringOperations() {
        TaxonomyNodeId from = library.topic();
        TaxonomyNodeId to = library.topic();
        ProblemId id = library.problem(from);
        LoggedIn.as(Role.TEACHER);

        assertThatThrownBy(() -> problems.rehomeTopic(from, to))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> problems.distribute(from, Map.of(id, to)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.findById(id).orElseThrow().topics()).containsExactly(from);
    }

    /** Сценарий «Разметка Методом, в Теме не встречавшимся». */
    @Test
    void methodNeverSeenInTheTopicMayStillBeUsedAndThenAppearsInItsSelection() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId newcomer = library.method();

        problems.create(null, ExamPart.FIRST, List.of(topic), List.of(newcomer), List.of(), pdf(), pdf());

        assertThat(problems.methodsUsedIn(topic))
                .as("выборка не ограничивает выбор, а следует за ним")
                .extracting(method -> method.id())
                .contains(newcomer);
    }

    private static UploadedFile pdf() {
        return new UploadedFile(TestLibrary.pdf(), ru.locus.file.FileType.PDF);
    }
}
