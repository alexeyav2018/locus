package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 3.2: хранение Учеников — запись читается обратно, список приходит
 * по алфавиту, а главное — каждый метод отвечает только своему владельцу.
 *
 * Изоляция проверяется на КАЖДОМ методе двумя владельцами: А видит
 * и правит своё, на чужом получает пусто или не меняет ни одной строки.
 * Форму — «у каждого метода есть {@link UserId}» — стережёт
 * {@code OwnerIsRequiredByStudentsTest}; здесь проверяется поведение,
 * потому что параметр можно принять и не использовать в запросе,
 * и тогда сигнатура правильная, а данные — чужие.
 *
 * Проверка идёт на настоящей базе в контейнере: половина проверяемого
 * живёт в SQL, а не в Java. Владельцы — настоящие учётные записи, потому
 * что ключ {@code fk_student_user} не даст завести Ученика у выдуманного.
 */
class StudentRepositoryTest extends IntegrationTest {

    @Autowired
    private StudentRepository students;

    @Autowired
    private TestAccounts accounts;

    private UserId alice;
    private UserId bob;

    @BeforeEach
    void twoTeachers() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
    }

    @Test
    void createdStudentIsReadBackByItsOwner() {
        String name = unique("Иванов Пётр");

        StudentId id = students.create(alice, name);

        Student found = students.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.owner()).isEqualTo(alice);
        assertThat(found.name()).isEqualTo(name);
    }

    @Test
    void anotherOwnerDoesNotFindTheStudent() {
        StudentId id = students.create(alice, unique("Иванов Пётр"));

        assertThat(students.findById(bob, id))
                .as("чужой Ученик неотличим от несуществующего")
                .isEmpty();
    }

    @Test
    void listHoldsOnlyOwnStudents() {
        StudentId mine = students.create(alice, unique("Иванов Пётр"));
        StudentId theirs = students.create(bob, unique("Сидорова Анна"));

        assertThat(students.findAll(alice)).extracting(Student::id).contains(mine).doesNotContain(theirs);
        assertThat(students.findAll(bob)).extracting(Student::id).contains(theirs).doesNotContain(mine);
    }

    @Test
    void studentsComeInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        students.create(alice, "cc-" + mark);
        students.create(alice, "aa-" + mark);
        students.create(alice, "bb-" + mark);

        List<String> mine = students.findAll(alice).stream()
                .map(Student::name)
                .filter(name -> name.endsWith(mark))
                .toList();

        assertThat(mine)
                .as("порядок показа не совпадает с порядком заведения — он алфавитный")
                .containsExactly("aa-" + mark, "bb-" + mark, "cc-" + mark);
    }

    /** Однофамильцы у одного владельца допустимы: уникальности имени нет. */
    @Test
    void twoStudentsMayShareTheName() {
        String name = unique("Иванов Пётр");

        StudentId first = students.create(alice, name);
        StudentId second = students.create(alice, name);

        assertThat(first).isNotEqualTo(second);
        assertThat(students.findAll(alice))
                .filteredOn(student -> student.name().equals(name))
                .extracting(Student::id)
                .containsExactly(first, second);
    }

    @Test
    void renamedStudentKeepsItsIdentifier() {
        StudentId id = students.create(alice, unique("Иванов Пётр"));
        String renamed = unique("Иванов-Петров Пётр");

        students.rename(alice, id, renamed);

        Student found = students.findById(alice, id).orElseThrow();
        assertThat(found.id()).as("Задания и отметки будут ссылаться на идентификатор, а не на имя").isEqualTo(id);
        assertThat(found.name()).isEqualTo(renamed);
    }

    @Test
    void anotherOwnerCannotRenameTheStudent() {
        String name = unique("Иванов Пётр");
        StudentId id = students.create(alice, name);

        students.rename(bob, id, unique("Чужое имя"));

        assertThat(students.findById(alice, id).orElseThrow().name())
                .as("правка чужого Ученика не меняет ни одной строки")
                .isEqualTo(name);
    }

    @Test
    void deletedStudentDisappears() {
        StudentId gone = students.create(alice, unique("Иванов Пётр"));
        StudentId kept = students.create(alice, unique("Сидорова Анна"));

        students.delete(alice, gone);

        assertThat(students.findById(alice, gone)).isEmpty();
        assertThat(students.findAll(alice)).extracting(Student::id).doesNotContain(gone).contains(kept);
    }

    @Test
    void anotherOwnerCannotDeleteTheStudent() {
        StudentId id = students.create(alice, unique("Иванов Пётр"));

        students.delete(bob, id);

        assertThat(students.findById(alice, id))
                .as("удаление чужого Ученика не меняет ни одной строки")
                .isPresent();
    }

    @Test
    void unknownIdentifierIsNotFound() {
        long free = students.findAll(alice).stream()
                .mapToLong(student -> student.id().value())
                .max()
                .orElse(0) + 1_000_000;

        assertThat(students.findById(alice, new StudentId(free))).isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
