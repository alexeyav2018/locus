package ru.locus.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Задача 2.1: идентификатор файла Работы — положительное число и отдельный
 * тип. Файл адресуется им, а не ключом хранилища: ключ наружу не отдаётся
 * (ADR-0021).
 */
class StudentWorkFileIdTest {

    @Test
    void zeroIsNotAnIdentifier() {
        assertThatThrownBy(() -> new StudentWorkFileId(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("положительным");
    }

    @Test
    void negativeValueIsNotAnIdentifier() {
        assertThatThrownBy(() -> new StudentWorkFileId(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveValueIsKeptAsItIs() {
        assertThat(new StudentWorkFileId(12).value()).isEqualTo(12);
    }

    @Test
    void identifiersWithTheSameValueAreEqual() {
        assertThat(new StudentWorkFileId(7)).isEqualTo(new StudentWorkFileId(7));
    }
}
