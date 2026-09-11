package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import ru.locus.user.UserId;

/**
 * Задача 5.1: состав записи Группы — идентификатор, владелец и имя,
 * и больше ничего; состав Группы в записи не живёт.
 *
 * Правила те же, что у {@link StudentTest}: владелец обязателен
 * (инвариант 11 domain-model.md), имя непустое после отсечения пробелов.
 * Уникальность имени у владельца — дело репозитория и индекса,
 * и проверяется на базе в {@code GroupRepositoryTest}.
 */
class GroupTest {

    private static final GroupId ID = new GroupId(1);
    private static final UserId OWNER = new UserId(5);

    @Test
    void groupIsMadeOfIdentifierOwnerAndName() {
        Group group = new Group(ID, OWNER, "9Б");

        assertThat(group.id()).isEqualTo(ID);
        assertThat(group.owner()).isEqualTo(OWNER);
        assertThat(group.name()).isEqualTo("9Б");
    }

    @Test
    void emptyNameIsNotAGroup() {
        assertThatThrownBy(() -> new Group(ID, OWNER, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> new Group(ID, OWNER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupWithoutOwnerIsRefused() {
        assertThatThrownBy(() -> new Group(ID, null, "9Б"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("владелец");
    }

    /**
     * Состав Группы — не поле записи, а отдельная таблица: список
     * читается и задаётся через репозиторий, и запись не тащит его
     * за собой в каждый список Групп.
     */
    @Test
    void recordCarriesNothingButIdentifierOwnerAndName() {
        assertThat(Group.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "owner", "name");
    }
}
