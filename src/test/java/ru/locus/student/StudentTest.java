package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import ru.locus.user.UserId;

/**
 * Задача 3.1: состав записи Ученика — идентификатор, владелец и имя,
 * и больше ничего.
 *
 * Владелец обязателен: это первая запись личного контура, и Ученик без
 * владельца — Ученик, которого увидят все (инвариант 11 domain-model.md).
 * Имя обязательно и не пустое после отсечения пробелов; уникальности
 * у него нет — однофамильцы обычны, и это проверяется на репозитории.
 */
class StudentTest {

    private static final StudentId ID = new StudentId(1);
    private static final UserId OWNER = new UserId(5);

    @Test
    void studentIsMadeOfIdentifierOwnerAndName() {
        Student student = new Student(ID, OWNER, "Иванов Пётр");

        assertThat(student.id()).isEqualTo(ID);
        assertThat(student.owner()).isEqualTo(OWNER);
        assertThat(student.name()).isEqualTo("Иванов Пётр");
    }

    @Test
    void emptyNameIsNotAStudent() {
        assertThatThrownBy(() -> new Student(ID, OWNER, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> new Student(ID, OWNER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void studentWithoutOwnerIsRefused() {
        assertThatThrownBy(() -> new Student(ID, null, "Иванов Пётр"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("владелец");
    }

    /**
     * Имени входа и пароля в записи нет: Ученик — объект учёта,
     * а не пользователь (ADR-0003). Таблицу тем же образом сторожит
     * {@code StudentIsNotAUserTest}.
     */
    @Test
    void recordCarriesNothingButIdentifierOwnerAndName() {
        assertThat(Student.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "owner", "name");
    }
}
