package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 3.1: хранение Раздач — запись читается обратно, список идёт
 * новыми первыми, а главное — каждый метод отвечает только своему
 * владельцу.
 *
 * Как в {@link ru.locus.student.GroupRepositoryTest}, изоляция проверяется
 * на КАЖДОМ методе двумя владельцами. Сверх того — то, что держит схема:
 * Раздача с Заданиями не удаляется в обход сервиса, ключ
 * {@code fk_assignment_batch} без каскада.
 */
class AssignmentBatchRepositoryTest extends IntegrationTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 1);

    @Autowired
    private AssignmentBatchRepository batches;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestLibrary library;

    private UserId alice;
    private UserId bob;

    @BeforeEach
    void twoTeachers() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
    }

    @Test
    void createdBatchIsReadBackByItsOwner() {
        String name = unique("9Б");

        AssignmentBatchId id = batches.create(alice, name, ISSUED);

        AssignmentBatch found = batches.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.owner()).isEqualTo(alice);
        assertThat(found.groupName()).isEqualTo(name);
        assertThat(found.issuedOn()).isEqualTo(ISSUED);
    }

    @Test
    void anotherOwnerDoesNotFindTheBatch() {
        AssignmentBatchId id = batches.create(alice, unique("9Б"), ISSUED);

        assertThat(batches.findById(bob, id))
                .as("чужая Раздача неотличима от несуществующей")
                .isEmpty();
    }

    @Test
    void listHoldsOnlyOwnBatches() {
        AssignmentBatchId mine = batches.create(alice, unique("9Б"), ISSUED);
        AssignmentBatchId theirs = batches.create(bob, unique("9Б"), ISSUED);

        assertThat(batches.findAll(alice)).extracting(AssignmentBatch::id).contains(mine).doesNotContain(theirs);
        assertThat(batches.findAll(bob)).extracting(AssignmentBatch::id).contains(theirs).doesNotContain(mine);
    }

    /** Новые первыми: по дате выдачи, а в один день — по идентификатору, позже заведённая выше. */
    @Test
    void batchesComeNewestFirst() {
        String mark = UUID.randomUUID().toString();
        AssignmentBatchId old = batches.create(alice, "old-" + mark, ISSUED.minusDays(7));
        AssignmentBatchId earlierToday = batches.create(alice, "today-1-" + mark, ISSUED);
        AssignmentBatchId laterToday = batches.create(alice, "today-2-" + mark, ISSUED);
        AssignmentBatchId fresh = batches.create(alice, "fresh-" + mark, ISSUED.plusDays(1));

        List<AssignmentBatchId> mine = batches.findAll(alice).stream()
                .filter(batch -> batch.groupName().endsWith(mark))
                .map(AssignmentBatch::id)
                .toList();

        assertThat(mine).containsExactly(fresh, laterToday, earlierToday, old);
    }

    @Test
    void deletedBatchDisappears() {
        AssignmentBatchId gone = batches.create(alice, unique("9Б"), ISSUED);
        AssignmentBatchId kept = batches.create(alice, unique("10Б"), ISSUED);

        batches.delete(alice, gone);

        assertThat(batches.findById(alice, gone)).isEmpty();
        assertThat(batches.findAll(alice)).extracting(AssignmentBatch::id).doesNotContain(gone).contains(kept);
    }

    @Test
    void anotherOwnerCannotDeleteTheBatch() {
        AssignmentBatchId id = batches.create(alice, unique("9Б"), ISSUED);

        batches.delete(bob, id);

        assertThat(batches.findById(alice, id))
                .as("удаление чужой Раздачи не меняет ни одной строки")
                .isPresent();
    }

    /**
     * Раздача с Заданиями не удаляется в обход сервиса: ключ
     * {@code fk_assignment_batch} без каскада. Раздача уходит целиком
     * или никак, и решает это сервис, сняв Задания первыми (ADR-0037).
     */
    @Test
    void batchWithAssignmentsIsHeldByTheSchema() {
        AssignmentBatchId batch = batches.create(alice, unique("9Б"), ISSUED);
        StudentId student = students.create(alice, unique("Иванов Пётр"));
        TaxonomyNodeId topic = library.topic();
        assignments.create(alice, student, batch, ISSUED, ISSUED.plusDays(7), TheoryScope.NONE,
                List.of(library.problem(topic)));

        assertThatThrownBy(() -> batches.delete(alice, batch))
                .isInstanceOf(DataIntegrityViolationException.class);

        assignments.deleteByBatch(alice, batch);
        batches.delete(alice, batch);
        assertThat(batches.findById(alice, batch)).as("без Заданий Раздача удаляется").isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
