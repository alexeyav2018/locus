package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.NOT_MASTERED;
import static ru.locus.mastery.MasteryStatus.UNKNOWN;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.EntryInUseException;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.problem.ExamPart;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 4.3: требование словарей «Удаляется только неиспользуемая запись» —
 * та его половина, что ждала отметок Владения. Метод, на который опирается
 * хотя бы одна отметка любого Учителя, Администратор не удаляет.
 *
 * Как в {@code ProblemsGuardTheDictionariesTest}, проверяется через сервис
 * словаря, а не через ответчик: важно, что словарь отказывает. Сторона
 * ADR-0036 — число считает отметки обоих Учителей, которых Администратор
 * не видит. Отметка ставится репозиторием: Задача для неё здесь не нужна,
 * ключ держит только Метод, Тему и Ученика.
 */
class MasteryGuardsTheMethodTest extends IntegrationTest {

    @Autowired
    private SolutionMethodService methods;

    @Autowired
    private CharacteristicService characteristics;

    @Autowired
    private ProblemService problems;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId alice;
    private UserId bob;
    private StudentId alicesPupil;
    private StudentId bobsPupil;
    private TaxonomyNodeId topic;

    @BeforeEach
    void twoTeachersWithPupilsAndAnAdministratorAtTheDictionary() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        alicesPupil = students.create(alice, "Иванов Пётр-" + UUID.randomUUID());
        bobsPupil = students.create(bob, "Сидорова Анна-" + UUID.randomUUID());
        topic = library.topic();
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Удаление Метода, на который опираются отметки». */
    @Test
    void methodUnderMarksOfAnyTeacherIsNotDeleted() {
        SolutionMethodId method = library.method();
        marks.put(alice, alicesPupil, topic, method, MASTERED);
        marks.put(bob, bobsPupil, topic, method, NOT_MASTERED);

        assertThatThrownBy(() -> methods.delete(method))
                .isInstanceOf(EntryInUseException.class)
                .hasMessageContaining("используется")
                .as("число — по обоим Учителям (ADR-0036)")
                .hasMessageContaining("отметки Владения (2)");

        assertThat(methods.method(method)).as("запись на месте").isNotNull();
        assertThat(marks.countByMethod(method)).as("отметки на месте").isEqualTo(2);
    }

    /** Сценарий «Запись, освободившаяся от употребления»: сняты и отметки, и разметка. */
    @Test
    void methodFreedFromMarksAndMarkupIsDeleted() {
        SolutionMethodId leaving = library.method();
        SolutionMethodId staying = library.method();
        ProblemId problem = library.problem(List.of(topic), List.of(leaving, staying), List.of(), ExamPart.FIRST);
        marks.put(alice, alicesPupil, topic, leaving, MASTERED);

        assertThatThrownBy(() -> methods.delete(leaving)).isInstanceOf(EntryInUseException.class);

        marks.put(alice, alicesPupil, topic, leaving, UNKNOWN);
        problems.edit(problem, null, ExamPart.FIRST, List.of(topic), List.of(staying), List.of());

        assertThatCode(() -> methods.delete(leaving))
                .as("ни отметок, ни разметки — условие перестало выполняться")
                .doesNotThrowAnyException();
    }

    /** Характеристику отметки не держат: в ячейке владения её нет. */
    @Test
    void marksDoNotHoldACharacteristic() {
        CharacteristicId characteristic = library.characteristic();
        marks.put(alice, alicesPupil, topic, library.method(), MASTERED);

        assertThatCode(() -> characteristics.delete(characteristic)).doesNotThrowAnyException();
    }
}
