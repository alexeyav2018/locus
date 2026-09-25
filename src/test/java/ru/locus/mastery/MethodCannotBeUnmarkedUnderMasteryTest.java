package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.locus.mastery.MasteryStatus.MASTERED;

import java.time.LocalDate;
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
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentRepository;
import ru.locus.assignment.TheoryScope;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.file.FileKey;
import ru.locus.problem.ExamPart;
import ru.locus.problem.Problem;
import ru.locus.problem.ProblemId;
import ru.locus.problem.ProblemInUseException;
import ru.locus.problem.ProblemService;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkRepository;

/**
 * Задача 18 бэклога ({@code method-edit-impact}): половина открытого вопроса
 * ADR-0011, раздел «Последствия», — «правка разметки Задачи задним числом
 * может оставить ячейку Владения без задач». Симметричная половина (удаление
 * Метода из словаря) закрыта {@link MasteryGuardsTheMethodTest}.
 *
 * <p>Эта половина закрыта не отдельным правилом, а стечением двух решений,
 * не рассчитанных специально на этот случай: Задача, вошедшая в Задание,
 * не правится рукой, пока такие Задания есть (ADR-0030), а Задание
 * с Работой не удаляется (ADR-0037). Отметка Владения ставится только
 * по принятой Работе ({@code POST /mastery}), значит Задача, на паре
 * которой уже стоит отметка, всегда входит в неудаляемое Задание —
 * и поэтому заморожена навсегда. Снять с неё Метод невозможно: правка
 * отклоняется целиком, до разбора состава разметки.
 *
 * <p>Тест не проверяет отдельное правило — он фиксирует текущее сочетание
 * трёх ADR (0011, 0030, 0037). Начнёт падать — значит, одно из них
 * изменилось и вопрос ADR-0011 снова открыт.
 */
class MethodCannotBeUnmarkedUnderMasteryTest extends IntegrationTest {

    @Autowired
    private ProblemService problems;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentWorkRepository works;

    @Autowired
    private MasteryRepository marks;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId owner;
    private StudentId student;
    private TaxonomyNodeId topic;
    private SolutionMethodId markedMethod;
    private SolutionMethodId otherMethod;
    private ProblemId problem;

    @BeforeEach
    void issueAcceptAndMarkAProblem() {
        TestAccounts.Account teacher = accounts.settled(Role.TEACHER);
        LoggedIn.as(teacher);
        owner = teacher.id();
        student = students.create(owner, TestLibrary.unique("Иванов Пётр"));
        topic = library.topic();
        markedMethod = library.method();
        otherMethod = library.method();
        problem = library.problem(List.of(topic), List.of(markedMethod, otherMethod), List.of(), ExamPart.FIRST);

        AssignmentId assignment = assignments.create(owner, student, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), TheoryScope.NONE, List.of(problem));
        works.create(owner, assignment, problem, LocalDate.of(2026, 9, 3), null, "",
                List.of(new FileKey(UUID.randomUUID() + ".jpg")));
        marks.put(owner, student, topic, markedMethod, MASTERED);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    @Test
    void unmarkingTheMethodIsRefusedAndNothingChanges() {
        Problem current = problems.problem(problem);

        LoggedIn.as(Role.ADMINISTRATOR);
        assertThatThrownBy(() -> problems.edit(problem, current.caption(), current.part(),
                current.topics(), List.of(otherMethod), List.of()))
                .as("Задача заморожена Заданием (ADR-0030), правка отклоняется целиком")
                .isInstanceOf(ProblemInUseException.class)
                .hasMessageContaining("вошла в Задания (1)");

        assertThat(problems.problem(problem).methods())
                .as("разметка не изменилась")
                .containsExactlyInAnyOrder(markedMethod, otherMethod);
        Map<Cell, MasteryStatus> statuses = marks.findByStudent(owner, student);
        assertThat(statuses.get(new Cell(topic, markedMethod)))
                .as("отметка не изменилась")
                .isEqualTo(MASTERED);
    }
}
