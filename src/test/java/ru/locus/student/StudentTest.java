package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import ru.locus.user.UserId;

/**
 * Задача 3.1: состав записи Ученика — идентификатор, владелец, имя
 * и состояние выбытия, и больше ничего.
 *
 * Владелец обязателен: это первая запись личного контура, и Ученик без
 * владельца — Ученик, которого увидят все (инвариант 11 domain-model.md).
 * Имя обязательно и не пустое после отсечения пробелов; уникальности
 * у него нет — однофамильцы обычны, и это проверяется на репозитории.
 * Выбытие (ADR-0040) — отдельный булев признак без собственных ограничений.
 */
class StudentTest {

    private static final StudentId ID = new StudentId(1);
    private static final UserId OWNER = new UserId(5);

    @Test
    void studentIsMadeOfIdentifierOwnerAndName() {
        Student student = new Student(ID, OWNER, "Иванов Пётр", false);

        assertThat(student.id()).isEqualTo(ID);
        assertThat(student.owner()).isEqualTo(OWNER);
        assertThat(student.name()).isEqualTo("Иванов Пётр");
        assertThat(student.withdrawn()).isFalse();
    }

    @Test
    void emptyNameIsNotAStudent() {
        assertThatThrownBy(() -> new Student(ID, OWNER, "   ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> new Student(ID, OWNER, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void studentWithoutOwnerIsRefused() {
        assertThatThrownBy(() -> new Student(ID, null, "Иванов Пётр", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("владелец");
    }

    /**
     * Имени входа и пароля в записи нет: Ученик — объект учёта,
     * а не пользователь (ADR-0003). Таблицу тем же образом сторожит
     * {@code StudentIsNotAUserTest}.
     */
    @Test
    void recordCarriesIdentifierOwnerNameAndWithdrawnAndNothingElse() {
        assertThat(Student.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "owner", "name", "withdrawn");
    }
}
