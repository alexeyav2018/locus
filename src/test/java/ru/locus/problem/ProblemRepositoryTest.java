package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethod;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Хранение Задач: Задача читается обратно вместе со всей разметкой, список
 * Темы приходит по номеру, а выборка Методов Темы идёт по разметке Задач.
 *
 * Проверка идёт на настоящей базе в контейнере — подмены репозитория нет:
 * половина проверяемого здесь живёт в SQL и во внешних ключах, а не в Java.
 *
 * Тесты идут на одной базе, поэтому обстановку каждый заводит себе сам,
 * с неповторяющимися именами ({@link TestLibrary}).
 */
class ProblemRepositoryTest extends IntegrationTest {

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private TestLibrary library;

    @Test
    void createdProblemIsReadBackWithAllItsMarkup() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();
        FileKey condition = library.storedPdf();
        FileKey solution = library.storedPdf();

        ProblemId id = problems.create("Ященко, вариант 12", ExamPart.SECOND, condition, solution,
                List.of(topic), List.of(method), List.of(characteristic));

        Problem found = problems.findById(id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.caption()).isEqualTo("Ященко, вариант 12");
        assertThat(found.part()).isEqualTo(ExamPart.SECOND);
        assertThat(found.conditionFile()).isEqualTo(condition);
        assertThat(found.solutionFile()).isEqualTo(solution);
        assertThat(found.topics()).containsExactly(topic);
        assertThat(found.methods()).containsExactly(method);
        assertThat(found.characteristics()).containsExactly(characteristic);
    }

    /** Сценарий «Задача без подписи»: подпись хранится как отсутствующая. */
    @Test
    void problemWithoutACaptionIsStoredAndReadBack() {
        TaxonomyNodeId topic = library.topic();

        ProblemId id = problems.create(null, ExamPart.FIRST, library.storedPdf(), library.storedPdf(),
                List.of(topic), List.of(library.method()), List.of());

        assertThat(problems.findById(id).orElseThrow().hasCaption()).isFalse();
    }

    /** Сценарий «Номер выдан при заведении». */
    @Test
    void everyProblemGetsANumberGreaterThanTheOneBefore() {
        TaxonomyNodeId topic = library.topic();

        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        assertThat(second.value())
                .as("номер монотонно растёт: по нему Задача называется в разговоре")
                .isGreaterThan(first.value());
    }

    /** Сценарий «Список Задач Темы»: порядок — по номеру. */
    @Test
    void problemsOfATopicComeByNumber() {
        TaxonomyNodeId topic = library.topic();
        ProblemId first = library.problem(topic);
        ProblemId second = library.problem(topic);

        assertThat(problems.findByTopic(topic))
                .extracting(Problem::id)
                .containsExactly(first, second);
    }

    /** Сценарий «Тема без Задач». */
    @Test
    void topicWithoutProblemsGivesAnEmptyList() {
        assertThat(problems.findByTopic(library.topic())).isEmpty();
    }

    /** Сценарий «Задача, размеченная двумя Темами». */
    @Test
    void problemMarkedWithTwoTopicsIsListedUnderBoth() {
        TaxonomyNodeId first = library.topic();
        TaxonomyNodeId second = library.topic();

        ProblemId id = problems.create(null, ExamPart.FIRST, library.storedPdf(), library.storedPdf(),
                List.of(first, second), List.of(library.method()), List.of());

        assertThat(problems.findByTopic(first)).extracting(Problem::id).contains(id);
        assertThat(problems.findByTopic(second)).extracting(Problem::id).contains(id);
    }

    /** Сценарий «Методы Темы». */
    @Test
    void methodsOfATopicComeFromTheMarkupOfItsProblemsWithoutRepetition() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId shared = library.method();
        SolutionMethodId second = library.method();
        SolutionMethodId third = library.method();
        problems.create(null, ExamPart.FIRST, library.storedPdf(), library.storedPdf(),
                List.of(topic), List.of(shared, second), List.of());
        problems.create(null, ExamPart.FIRST, library.storedPdf(), library.storedPdf(),
                List.of(topic), List.of(shared, third), List.of());

        assertThat(problems.findMethodsUsedInTopic(topic))
                .as("каждый Метод один раз, хотя один из них употреблён дважды")
                .extracting(SolutionMethod::id)
                .containsExactlyInAnyOrder(shared, second, third);
    }

    /** Сценарий «Тема без Задач»: выборка Методов пуста. */
    @Test
    void topicWithoutProblemsUsesNoMethods() {
        assertThat(problems.findMethodsUsedInTopic(library.topic())).isEmpty();
    }

    /** Сценарий «Метод из другой Темы». */
    @Test
    void methodUsedOnlyInANeighbouringTopicDoesNotAppear() {
        TaxonomyNodeId here = library.topic();
        TaxonomyNodeId elsewhere = library.topic();
        SolutionMethodId alien = library.method();
        library.problem(elsewhere, alien);
        library.problem(here);

        assertThat(problems.findMethodsUsedInTopic(here))
                .extracting(SolutionMethod::id)
                .doesNotContain(alien);
    }

    /** Сценарий «Разметка изменена». */
    @Test
    void markupIsReplacedWhole() {
        TaxonomyNodeId topic = library.topic();
        ProblemId id = library.problem(topic);
        SolutionMethodId replacement = library.method();

        problems.replaceMarkup(id, List.of(topic), List.of(replacement), List.of(library.characteristic()));

        Problem found = problems.findById(id).orElseThrow();
        assertThat(found.methods())
                .as("замена, а не досыпание: снятый Метод исчезает")
                .containsExactly(replacement);
        assertThat(found.characteristics()).hasSize(1);
        assertThat(found.id()).as("номер при правке не меняется").isEqualTo(id);
    }

    @Test
    void captionAndPartAreChangedWithoutTouchingTheNumber() {
        ProblemId id = library.problem(library.topic());

        problems.changeCaption(id, "Ященко, вариант 3");
        problems.changePart(id, ExamPart.FIRST);

        Problem found = problems.findById(id).orElseThrow();
        assertThat(found.caption()).isEqualTo("Ященко, вариант 3");
        assertThat(found.part()).isEqualTo(ExamPart.FIRST);
        assertThat(found.id()).isEqualTo(id);
    }

    @Test
    void fileKeysAreReplacedOneAtATime() {
        ProblemId id = library.problem(library.topic());
        FileKey oldSolution = problems.findById(id).orElseThrow().solutionFile();
        FileKey newSolution = library.storedPdf();

        problems.changeSolutionFile(id, newSolution);

        Problem found = problems.findById(id).orElseThrow();
        assertThat(found.solutionFile()).isEqualTo(newSolution).isNotEqualTo(oldSolution);
        assertThat(found.conditionFile())
                .as("второй файл при замене не задет")
                .isEqualTo(problems.findById(id).orElseThrow().conditionFile());
    }

    /** Сценарий «Задача удалена» и «Разметка соседних Задач не задета». */
    @Test
    void deletedProblemTakesItsMarkupAndLeavesTheNeighboursAlone() {
        TaxonomyNodeId topic = library.topic();
        ProblemId doomed = library.problem(topic);
        ProblemId neighbour = library.problem(topic);

        problems.delete(doomed);

        assertThat(problems.findById(doomed)).isEmpty();
        assertThat(problems.findByTopic(topic)).extracting(Problem::id).containsExactly(neighbour);
        assertThat(problems.findById(neighbour).orElseThrow().methods()).isNotEmpty();
    }

    @Test
    void countsAnswerTheQuestionsOtherAreasAsk() {
        TaxonomyNodeId topic = library.topic();
        SolutionMethodId method = library.method();
        CharacteristicId characteristic = library.characteristic();
        problems.create(null, ExamPart.FIRST, library.storedPdf(), library.storedPdf(),
                List.of(topic), List.of(method), List.of(characteristic));

        assertThat(problems.countByTopic(topic)).isEqualTo(1);
        assertThat(problems.countByMethod(method)).isEqualTo(1);
        assertThat(problems.countByCharacteristic(characteristic)).isEqualTo(1);
    }

    /**
     * Внешние ключи — второй рубеж: ссылка на несуществующий узел или запись
     * словаря отклоняется базой, даже если проверка в сервисе окажется
     * однажды обойдённой.
     */
    @Test
    void referenceToSomethingThatDoesNotExistIsRefusedByTheDatabase() {
        TaxonomyNodeId missingNode = new TaxonomyNodeId(Long.MAX_VALUE);
        SolutionMethodId missingMethod = new SolutionMethodId(Long.MAX_VALUE);

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                library.storedPdf(), library.storedPdf(),
                List.of(missingNode), List.of(library.method()), List.of()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> problems.create(null, ExamPart.FIRST,
                library.storedPdf(), library.storedPdf(),
                List.of(library.topic()), List.of(missingMethod), List.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownProblemIsSimplyAbsent() {
        assertThat(problems.findById(new ProblemId(Long.MAX_VALUE))).isEmpty();
    }
}
