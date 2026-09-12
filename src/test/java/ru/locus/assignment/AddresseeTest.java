package ru.locus.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.locus.student.GroupId;
import ru.locus.student.StudentId;

/**
 * Задача 2.3: адресат выдачи ровно один — Ученик либо Группа.
 *
 * Правило проверяется в типе области, а не в контроллере: форма присылает
 * два необязательных числа, и «ни одного» с «обоими» отклоняются одним
 * сообщением из спеки — «адресат один: Ученик либо Группа».
 */
class AddresseeTest {

    @Test
    void studentAloneAddressesTheStudent() {
        Addressee addressee = Addressee.of(7L, null);

        assertThat(addressee).isEqualTo(new Addressee.ToStudent(new StudentId(7)));
    }

    @Test
    void groupAloneAddressesTheGroup() {
        Addressee addressee = Addressee.of(null, 3L);

        assertThat(addressee).isEqualTo(new Addressee.ToGroup(new GroupId(3)));
    }

    @Test
    void neitherIsRefused() {
        assertThatThrownBy(() -> Addressee.of(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("адресат один: Ученик либо Группа");
    }

    @Test
    void bothAreRefused() {
        assertThatThrownBy(() -> Addressee.of(7L, 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("адресат один: Ученик либо Группа");
    }

    /**
     * Тип запечатан на двух вариантах: {@code switch} по адресату обязан
     * разобрать оба, и третьего добавить незаметно нельзя.
     */
    @Test
    void thereAreExactlyTwoKindsOfAddressee() {
        assertThat(Addressee.class.isSealed()).isTrue();
        assertThat(Addressee.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(Addressee.ToStudent.class, Addressee.ToGroup.class);
    }
}
