package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ru.locus.user.UserId;

/**
 * Задача 2.2: состав записи Раздачи — идентификатор, владелец, имя Группы
 * текстом и дата выдачи; больше ничего.
 *
 * Раздача помнит имя, а не Группу: Группа удаляется в любой момент
 * (ADR-0035), а «выдано 9Б такого-то числа» — исторический факт.
 * Срока и охвата у Раздачи нет — они на Заданиях (ADR-0016).
 */
class AssignmentBatchTest {

    private static final AssignmentBatchId ID = new AssignmentBatchId(1);
    private static final UserId OWNER = new UserId(5);
    private static final LocalDate ISSUED = LocalDate.of(2026, 9, 10);

    @Test
    void batchIsMadeOfIdentifierOwnerGroupNameAndDate() {
        AssignmentBatch batch = new AssignmentBatch(ID, OWNER, "9Б", ISSUED);

        assertThat(batch.id()).isEqualTo(ID);
        assertThat(batch.owner()).isEqualTo(OWNER);
        assertThat(batch.groupName()).isEqualTo("9Б");
        assertThat(batch.issuedOn()).isEqualTo(ISSUED);
    }

    @Test
    void emptyGroupNameIsRefused() {
        assertThatThrownBy(() -> new AssignmentBatch(ID, OWNER, "   ", ISSUED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> new AssignmentBatch(ID, OWNER, null, ISSUED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchWithoutOwnerIsRefused() {
        assertThatThrownBy(() -> new AssignmentBatch(ID, null, "9Б", ISSUED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("владелец");
    }

    @Test
    void batchWithoutDateIsRefused() {
        assertThatThrownBy(() -> new AssignmentBatch(ID, OWNER, "9Б", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("дата выдачи");
    }

    /**
     * Имя текстом, а не ключ на Группу, и ни срока, ни охвата: Раздача —
     * лёгкая сущность, помнит происхождение и не более (ADR-0016).
     */
    @Test
    void recordRemembersTheNameNotTheGroup() {
        assertThat(AssignmentBatch.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "owner", "groupName", "issuedOn");
    }
}
