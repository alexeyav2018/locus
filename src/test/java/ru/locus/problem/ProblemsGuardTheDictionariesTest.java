package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.EntryInUseException;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.file.FileType;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Требование словарей «Удаляется только неиспользуемая запись» — та его часть,
 * которая до появления Задач выполнялась тождественно и потому ничего
 * не проверяла.
 *
 * Долг, записанный при построении словарей, погашен здесь: разметка Задачи
 * теперь есть, и удаление употреблённой записи отклоняется. Оставшийся
 * долг — отметки Владения ({@code mastery-marks}), и он назван по-прежнему.
 */
class ProblemsGuardTheDictionariesTest extends IntegrationTest {

    @Autowired
    private SolutionMethodService methods;

    @Autowired
    private CharacteristicService characteristics;

    @Autowired
    private ProblemService problems;

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

    /** Сценарий «Удаление употреблённого Метода». */
    @Test
    void methodUsedInTheMarkupOfAProblemIsNotDeleted() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        ProblemId problem = library.problem(topic, method);

        assertThatThrownBy(() -> methods.delete(method))
                .isInstanceOf(EntryInUseException.class)
                .hasMessageContaining("используется");

        assertThat(methods.method(method)).as("запись на месте").isNotNull();
        assertThat(problems.problem(problem).methods())
                .as("разметка Задачи не изменилась")
                .containsExactly(method);
    }

    /** Сценарий «Удаление употреблённой Характеристики». */
    @Test
    void characteristicUsedInTheMarkupOfAProblemIsNotDeleted() {
        CharacteristicId characteristic = library.characteristic();
        problems.create(null, ExamPart.FIRST, List.of(library.topic()), List.of(library.method()),
                List.of(characteristic),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF));

        assertThatThrownBy(() -> characteristics.delete(characteristic))
                .isInstanceOf(EntryInUseException.class);

        assertThat(characteristics.characteristic(characteristic)).isNotNull();
    }

    /** Сценарий «Запись, освободившаяся от употребления». */
    @Test
    void methodFreedFromItsLastMarkupIsDeleted() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId leaving = library.method();
        SolutionMethodId staying = library.method();
        ProblemId problem = problems.create(null, ExamPart.FIRST, List.of(topic),
                List.of(leaving, staying), List.of(),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF),
                new UploadedFile(TestLibrary.pdf(), FileType.PDF));

        problems.edit(problem, null, ExamPart.FIRST, List.of(topic), List.of(staying), List.of());

        assertThatCode(() -> methods.delete(leaving))
                .as("Метод снят разметкой — условие перестало выполняться")
                .doesNotThrowAnyException();
    }

    /** Сценарий «Неиспользуемая запись удалена»: без разметки ничего не изменилось. */
    @Test
    void entryNothingRefersToIsStillDeletedAsBefore() {
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();

        assertThatCode(() -> methods.delete(method)).doesNotThrowAnyException();
        assertThatCode(() -> characteristics.delete(characteristic)).doesNotThrowAnyException();
    }

    /**
     * Сценарий «Удаление не задевает второй словарь»: словари независимы,
     * и общий вопрос не путает Метод с одноимённой Характеристикой.
     */
    @Test
    void markupOfAProblemDoesNotBlockTheOtherDictionary() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        CharacteristicId free = library.characteristic();
        library.problem(topic, method);

        assertThatCode(() -> characteristics.delete(free))
                .as("Характеристика, ничем не употреблённая, удаляется")
                .doesNotThrowAnyException();
        assertThat(methods.method(method)).isNotNull();
    }
}
