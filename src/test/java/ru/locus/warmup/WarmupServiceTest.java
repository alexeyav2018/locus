package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;
import static ru.locus.mastery.MasteryStatus.UNKNOWN;
import static ru.locus.warmup.WarmupOutcome.FAILED;
import static ru.locus.warmup.WarmupOutcome.SUCCEEDED;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.assignment.Addressee;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.mastery.Cell;
import ru.locus.mastery.Mark;
import ru.locus.mastery.MasteryCell;
import ru.locus.mastery.MasteryRepository;
import ru.locus.mastery.MasteryService;
import ru.locus.problem.ExamPart;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemInUseException;
import ru.locus.problem.ProblemService;
import ru.locus.student.GroupId;
import ru.locus.student.GroupService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentInUseException;
import ru.locus.student.StudentService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkRepository;
import ru.locus.work.Verdict;

/**
 * Задача 3.5: {@link WarmupService} и Владение по Разминке — сценарии
 * спеки {@code warmups} и второе основание отметки из {@code mastery-marks}
 * (ADR-0061).
 *
 * Разминка заводится Ученику или действующим членам Группы, помнит имя
 * Группы текстом, выбывшим не проводится. Итог ставится только участнику
 * и только по Задаче состава, пустая клетка не хранится, итог отметки
 * не ставит. Отметка по Разминке пишется без Работы, но только на паре
 * из Задач Разминки, и всё сохранение — одной транзакцией. Удаление
 * Разминки уносит итоги и оставляет отметки; Задача в Разминке заморожена,
 * участник не удаляется, Группа — удаляется.
 *
 * Вошедший — Учитель и Администратор сразу: заморозку Задачи проверяет
 * правка библиотеки, а она — только Администратору.
 */
class WarmupServiceTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Autowired
    private WarmupService service;

    @Autowired
    private WarmupRepository warmups;

    @Autowired
    private MasteryService mastery;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private StudentService students;

    @Autowired
    private GroupService groups;

    @Autowired
    private ProblemService problems;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;
    private TaxonomyNodeId topicA;
    private TaxonomyNodeId topicB;
    private SolutionMethodId methodX;
    private SolutionMethodId methodY;
    private ProblemId first;
    private ProblemId second;

    @BeforeEach
    void logInAndPrepareTwoProblems() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER, Role.ADMINISTRATOR);
        LoggedIn.as(teacher);
        owner = teacher.id();
        topicA = library.topic();
        topicB = library.topic();
        methodX = library.method();
        methodY = library.method();
        first = library.problem(topicA, methodX);
        second = library.problem(List.of(topicA, topicB), List.of(methodX, methodY), List.of(), ExamPart.SECOND);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Разминка Ученику»: участник один, Задачи в порядке выбора, имени Группы нет. */
    @Test
    void warmupForAStudentKeepsTheChosenOrder() {
        StudentId student = student("Иванов Пётр");

        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(second, first));

        ListedWarmup held = service.warmup(id);
        assertThat(held.warmup().heldOn()).isEqualTo(TODAY);
        assertThat(held.warmup().students()).containsExactly(student);
        assertThat(held.warmup().problems()).containsExactly(second, first);
        assertThat(held.warmup().groupName()).isNull();
        assertThat(held.addressee()).as("Разминка Ученику названа его именем")
                .isEqualTo(students.student(student).name());
    }

    /** Сценарий «Повтор Задачи»: Задача в составе один раз. */
    @Test
    void repeatedProblemIsKeptOnce() {
        WarmupId id = service.hold(new Addressee.ToStudent(student("Иванов Пётр")), TODAY,
                List.of(first, second, first));

        assertThat(service.warmup(id).warmup().problems()).containsExactly(first, second);
    }

    /** Сценарий «Прошедшая дата». */
    @Test
    void pastDateIsAccepted() {
        WarmupId id = service.hold(new Addressee.ToStudent(student("Иванов Пётр")), TODAY.minusWeeks(1),
                List.of(first));

        assertThat(service.warmup(id).warmup().heldOn()).isEqualTo(TODAY.minusWeeks(1));
    }

    /** Сценарий «Разминка без Задач». */
    @Test
    void warmupWithoutProblemsIsRefused() {
        StudentId student = student("Иванов Пётр");

        assertThatThrownBy(() -> service.hold(new Addressee.ToStudent(student), TODAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна Задача");
        assertThat(service.list(student)).isEmpty();
    }

    /** Сценарии «Разминка Группе» и «Группа переименована после Разминки». */
    @Test
    void warmupForAGroupRemembersItsNameAsText() {
        List<StudentId> members = List.of(student("Андреев"), student("Борисов"), student("Васильев"));
        String name = TestLibrary.unique("9Б");
        GroupId group = groups.create(name);
        groups.setMembers(group, members);

        WarmupId id = service.hold(new Addressee.ToGroup(group), TODAY, List.of(first));
        groups.rename(group, TestLibrary.unique("10Б"));

        ListedWarmup held = service.warmup(id);
        assertThat(held.warmup().students()).containsExactlyInAnyOrderElementsOf(members);
        assertThat(held.addressee()).isEqualTo(name);
        assertThat(held.participants()).hasSize(3);
    }

    /** Сценарий «Группа с выбывшим членом»: участники — действующие, итог — только им. */
    @Test
    void withdrawnMembersOfAGroupDoNotTakePart() {
        StudentId active = student("Андреев");
        StudentId alsoActive = student("Борисов");
        StudentId gone = student("Васильев");
        GroupId group = groups.create(TestLibrary.unique("9Б"));
        groups.setMembers(group, List.of(active, alsoActive, gone));
        students.withdraw(gone);

        WarmupId id = service.hold(new Addressee.ToGroup(group), TODAY, List.of(first));

        assertThat(service.warmup(id).warmup().students()).containsExactlyInAnyOrder(active, alsoActive);
        assertThat(groups.members(group)).as("состав Группы не меняется").hasSize(3);
        assertThatThrownBy(() -> service.record(id, gone, List.of(new Outcome(first, SUCCEEDED)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не участвует");
    }

    /** Сценарий «Выбывший Ученик прямо». */
    @Test
    void withdrawnStudentIsRefused() {
        StudentId student = student("Иванов Пётр");
        students.withdraw(student);

        assertThatThrownBy(() -> service.hold(new Addressee.ToStudent(student), TODAY, List.of(first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("выбыл");
        assertThat(service.list(student)).isEmpty();
    }

    /** Сценарий «Группа без действующих». */
    @Test
    void groupWithoutActiveMembersIsRefused() {
        StudentId student = student("Иванов Пётр");
        students.withdraw(student);
        GroupId group = groups.create(TestLibrary.unique("Все выбыли"));
        groups.setMembers(group, List.of(student));

        assertThatThrownBy(() -> service.hold(new Addressee.ToGroup(group), TODAY, List.of(first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не осталось действующих");
    }

    /** Сценарии «Итоги участника» и «Пустая клетка не хранится». */
    @Test
    void outcomesAreSetAndAnEmptyCellIsNotStored() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first, second));

        service.record(id, student, List.of(new Outcome(first, SUCCEEDED), new Outcome(second, FAILED)), List.of());

        Warmup warmup = service.warmup(id).warmup();
        assertThat(service.outcomesOf(warmup).get(student))
                .containsExactlyInAnyOrderEntriesOf(Map.of(first, SUCCEEDED, second, FAILED));

        service.record(id, student, List.of(new Outcome(second, null)), List.of());

        assertThat(service.outcomesOf(warmup).get(student))
                .as("пустая клетка — отсутствие итога, нетронутая Задача — как была")
                .containsExactlyEntriesOf(Map.of(first, SUCCEEDED));
    }

    /** Сценарий «Итог не участнику». */
    @Test
    void outcomeForANonParticipantIsRefused() {
        WarmupId id = service.hold(new Addressee.ToStudent(student("Иванов Пётр")), TODAY, List.of(first));
        StudentId stranger = student("Сидорова Анна");

        assertThatThrownBy(() -> service.record(id, stranger, List.of(new Outcome(first, SUCCEEDED)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.outcomesOf(service.warmup(id).warmup())).isEmpty();
    }

    /** Итог по Задаче не из состава — отказ. */
    @Test
    void outcomeForAProblemOutsideTheWarmupIsRefused() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first));

        assertThatThrownBy(() -> service.record(id, student, List.of(new Outcome(second, SUCCEEDED)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Такой Задачи в Разминке нет");
    }

    /** Сценарий «„Справился“ отметку не ставит». */
    @Test
    void succeededDoesNotMarkAnything() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first, second));

        service.record(id, student, List.of(new Outcome(first, SUCCEEDED), new Outcome(second, SUCCEEDED)), List.of());

        assertThat(marks.findByStudent(owner, student)).as("ни одной отметки").isEmpty();
    }

    /** Сценарий «Отметка по Разминке без Работы»: итог и отметка — одним сохранением. */
    @Test
    void markByWarmupIsWrittenWithoutAnyWork() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first));

        service.record(id, student, List.of(new Outcome(first, FAILED)), List.of(new Mark(topicA, methodX, MASTERED)));

        assertThat(marks.findByStudent(owner, student)).containsExactlyEntriesOf(
                Map.of(new Cell(topicA, methodX), MASTERED));
        assertThat(service.outcomesOf(service.warmup(id).warmup()).get(student)).containsEntry(first, FAILED);
    }

    /** Сценарий «Пара не из Задач Разминки»: ни итога, ни отметки. */
    @Test
    void pairOutsideTheWarmupRefusesBothOutcomeAndMark() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first));

        assertThatThrownBy(() -> service.record(id, student, List.of(new Outcome(first, SUCCEEDED)),
                List.of(new Mark(topicA, methodX, MASTERED), new Mark(topicB, methodY, MASTERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пара не из разметки");
        assertThat(marks.findByStudent(owner, student)).as("ни одной отметки").isEmpty();
        assertThat(service.outcomesOf(service.warmup(id).warmup())).as("и ни одного итога").isEmpty();
    }

    /**
     * Ячейки участника — объединение пар Задач без повторов, в порядке
     * состава и разметки: «А × X» у обеих Задач стоит один раз, первой —
     * как у первой Задачи. Порядок Методов разметки — по имени, а имена
     * в тесте случайные, поэтому ожидание строится из самой разметки.
     */
    @Test
    void cellsAreTheUnionOfPairsInOrder() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first, second));
        Problem markup = problems.problem(second);
        List<Cell> expected = new ArrayList<>(List.of(new Cell(topicA, methodX)));
        for (TaxonomyNodeId topic : markup.topics()) {
            for (SolutionMethodId method : markup.methods()) {
                Cell cell = new Cell(topic, method);
                if (!expected.contains(cell)) {
                    expected.add(cell);
                }
            }
        }

        Map<StudentId, List<MasteryCell>> cells = mastery.cellsOfWarmup(id);

        assertThat(cells).containsOnlyKeys(student);
        assertThat(expected).hasSize(4);
        assertThat(cells.get(student)).extracting(MasteryCell::cell).containsExactlyElementsOf(expected);
        assertThat(cells.get(student)).extracting(MasteryCell::status).containsOnly(UNKNOWN);
    }

    /** Справка показывает Работы и Разминки порознь: «Работы: 1 из 1 · разминки: 1 из 2». */
    @Test
    void hintShowsWorksAndWarmupsApart() {
        StudentId student = student("Иванов Пётр");
        AssignmentId assignment = assignments.create(owner, student, null, TODAY, TODAY.plusDays(7),
                TheoryScope.NONE, List.of(first));
        works.create(owner, assignment, first, TODAY, Verdict.CORRECT, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
        WarmupId yesterday = service.hold(new Addressee.ToStudent(student), TODAY.minusDays(1), List.of(first));
        service.record(yesterday, student, List.of(new Outcome(first, FAILED)), List.of());
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first));
        service.record(id, student, List.of(new Outcome(first, SUCCEEDED)), List.of());

        MasteryCell cell = mastery.cellsOfWarmup(id).get(student).get(0);

        assertThat(cell.hint()).isEqualTo("Работы: 1 из 1 · разминки: 1 из 2");
    }

    /** Сценарии «Удаление Разминки с итогами» и «Удаление Разминки оставляет отметки». */
    @Test
    void deletingAWarmupTakesOutcomesAndKeepsMarks() {
        StudentId student = student("Иванов Пётр");
        WarmupId id = service.hold(new Addressee.ToStudent(student), TODAY, List.of(first));
        service.record(id, student, List.of(new Outcome(first, SUCCEEDED)), List.of(new Mark(topicA, methodX, MASTERED)));

        service.delete(id);

        assertThatThrownBy(() -> service.warmup(id)).isInstanceOf(WarmupNotFoundException.class);
        assertThat(warmups.findOutcomes(owner, id)).as("итогов больше нет").isEmpty();
        assertThat(marks.findByStudent(owner, student)).as("отметка на паре осталась")
                .containsEntry(new Cell(topicA, methodX), MASTERED);
        assertThat(students.student(student)).as("Ученик остался").isNotNull();
        assertThat(problems.problem(first)).as("Задача осталась").isNotNull();
    }

    /** Сценарии «Задача в Разминке заморожена» и «Разминка удалена — Задача правится». */
    @Test
    void problemInAWarmupIsFrozenAndThawsAfterDeletion() {
        WarmupId id = service.hold(new Addressee.ToStudent(student("Иванов Пётр")), TODAY, List.of(first));
        Problem current = problems.problem(first);
        ExamPart otherPart = current.part() == ExamPart.FIRST ? ExamPart.SECOND : ExamPart.FIRST;

        assertThatThrownBy(() -> problems.edit(first, otherPart, current.topics(), current.methods(), List.of()))
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Разминки (1)");

        service.delete(id);
        problems.edit(first, otherPart, current.topics(), current.methods(), List.of());

        assertThat(problems.problem(first).part()).isEqualTo(otherPart);
    }

    /** Сценарии «Участник Разминки не удаляется» и «Группа с Разминкой удаляется». */
    @Test
    void participantIsKeptAndGroupIsDeleted() {
        StudentId member = student("Андреев");
        String name = TestLibrary.unique("9Б");
        GroupId group = groups.create(name);
        groups.setMembers(group, List.of(member));
        WarmupId id = service.hold(new Addressee.ToGroup(group), TODAY, List.of(first));

        assertThatThrownBy(() -> students.delete(member))
                .isInstanceOf(StudentInUseException.class)
                .hasMessageContaining("участвовал в Разминках (1)");

        groups.delete(group);

        ListedWarmup held = service.warmup(id);
        assertThat(held.addressee()).isEqualTo(name);
        assertThat(held.warmup().students()).containsExactly(member);
    }

    /** Сценарии «Список Разминок» и «Разминки Ученика». */
    @Test
    void listIsNewestFirstAndFiltersByParticipant() {
        StudentId pupil = student("Андреев");
        StudentId other = student("Борисов");
        GroupId group = groups.create(TestLibrary.unique("9Б"));
        groups.setMembers(group, List.of(pupil, other));
        WarmupId yesterday = service.hold(new Addressee.ToGroup(group), TODAY.minusDays(1), List.of(first));
        WarmupId today = service.hold(new Addressee.ToStudent(pupil), TODAY, List.of(first));
        WarmupId notHis = service.hold(new Addressee.ToStudent(other), TODAY.minusDays(2), List.of(first));

        assertThat(ids(service.list(null))).containsExactly(today, yesterday, notHis);
        assertThat(ids(service.list(pupil))).containsExactly(today, yesterday);
    }

    private StudentId student(String name) {
        return students.create(TestLibrary.unique(name));
    }

    private static List<WarmupId> ids(List<ListedWarmup> listed) {
        List<WarmupId> ids = new ArrayList<>();
        listed.forEach(row -> ids.add(row.warmup().id()));
        return ids;
    }
}
