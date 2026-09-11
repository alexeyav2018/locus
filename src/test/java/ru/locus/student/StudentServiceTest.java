package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.user.Role;

/**
 * Задача 4.2: правила Учеников — заведение, переименование, удаление, роль
 * и владелец.
 *
 * Все проверки стоят в сервисе, поэтому и проверяются на сервисе, а не через
 * экран: правило должно срабатывать при любом способе вызова, включая
 * контроллер, о котором сейчас никто не думает.
 *
 * Вошедший здесь — настоящая учётная запись ({@link LoggedIn#as(TestAccounts.Account)}),
 * а не одна роль в контексте: сервису личного контура нужен {@code UserId}
 * владельца, и он берёт его у {@code CurrentUser}.
 */
class StudentServiceTest extends IntegrationTest {

    @Autowired
    private StudentService students;

    @Autowired
    private StudentRepository repository;

    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account alice;

    @BeforeEach
    void logInAsATeacher() {
        alice = accounts.settled(Role.TEACHER);
        LoggedIn.as(alice);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарии «Заведение Ученика» и «Владелец — тот, кто завёл». */
    @Test
    void createdStudentBelongsToTheLoggedInTeacherAndAppearsInTheList() {
        String name = unique("Иванов Пётр");

        StudentId id = students.create(name);

        Student student = students.student(id);
        assertThat(student.name()).isEqualTo(name);
        assertThat(student.owner()).as("владелец подставлен из вошедшего, не из параметра").isEqualTo(alice.id());
        assertThat(students.all()).extracting(Student::id).contains(id);
    }

    /** Владелец не принимается из запроса: у заведения нет такого параметра. */
    @Test
    void nothingButANameIsAskedForAtCreation() {
        assertThat(StudentService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("create"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .as("владельца при заведении указать невозможно (ADR-0027)")
                        .containsExactly(String.class));
    }

    /** Сценарий «Пустое имя». */
    @Test
    void emptyNameCreatesNothing() {
        int before = students.all().size();

        assertThatThrownBy(() -> students.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> students.create(null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(students.all()).hasSize(before);
    }

    @Test
    void surroundingSpacesAreTrimmed() {
        String name = unique("Иванов Пётр");

        StudentId id = students.create("   " + name + "   ");

        assertThat(students.student(id).name()).isEqualTo(name);
    }

    /** Сценарий «Два Ученика с одним именем». */
    @Test
    void twoStudentsMayShareAName() {
        String name = unique("Иванов Пётр");

        StudentId first = students.create(name);
        StudentId second = students.create(name);

        assertThat(second).isNotEqualTo(first);
        assertThat(students.all()).extracting(Student::id).contains(first, second);
    }

    /** Сценарий «Учитель переименовывает Ученика». */
    @Test
    void renamedStudentKeepsItsIdentifierAndLeavesTheRestAlone() {
        StudentId renamed = students.create(unique("Иванов Пётр"));
        StudentId untouched = students.create(unique("Сидорова Анна"));
        String untouchedName = students.student(untouched).name();
        String newName = unique("Петров Иван");

        students.rename(renamed, newName);

        assertThat(students.student(renamed).name()).isEqualTo(newName);
        assertThat(students.student(untouched).name()).isEqualTo(untouchedName);
    }

    @Test
    void renamingToAnEmptyNameChangesNothing() {
        String name = unique("Иванов Пётр");
        StudentId id = students.create(name);

        assertThatThrownBy(() -> students.rename(id, " ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(students.student(id).name()).isEqualTo(name);
    }

    /** Сценарий «Удаление Ученика, на которого ничего не ссылается». */
    @Test
    void unreferencedStudentIsDeletedAndTheRestStay() {
        StudentId gone = students.create(unique("Иванов Пётр"));
        StudentId kept = students.create(unique("Сидорова Анна"));

        students.delete(gone);

        assertThat(students.all()).extracting(Student::id).doesNotContain(gone).contains(kept);
        assertThatThrownBy(() -> students.student(gone)).isInstanceOf(StudentNotFoundException.class);
    }

    /**
     * Сценарии «Прямой адрес чужого Ученика» и «Правка чужого Ученика»:
     * чужой Ученик для сервиса неотличим от несуществующего — тот же
     * отказ, и данные владельца не меняются.
     */
    @Test
    void anotherTeachersStudentIsIndistinguishableFromAMissingOne() {
        TestAccounts.Account bob = accounts.settled(Role.TEACHER);
        String bobsName = unique("Чужой Ученик");
        StudentId bobs = repository.create(bob.id(), bobsName);
        StudentId missing = new StudentId(bobs.value() + 1_000_000);

        assertThatThrownBy(() -> students.student(bobs)).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> students.student(missing)).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> students.rename(bobs, "Переименован")).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> students.rename(missing, "Переименован")).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> students.delete(bobs)).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> students.delete(missing)).isInstanceOf(StudentNotFoundException.class);

        assertThat(students.all()).extracting(Student::id).doesNotContain(bobs);
        Student untouched = repository.findById(bob.id(), bobs).orElseThrow();
        assertThat(untouched.name()).as("Ученик другого Учителя остался прежним").isEqualTo(bobsName);
    }

    /** Сценарий «Администратор без роли Учителя»: недоступно и чтение. */
    @Test
    void userWithoutTheTeacherRoleIsRefusedEverything() {
        StudentId existing = students.create(unique("Иванов Пётр"));
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(students::all).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> students.student(existing)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> students.create("Новый")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> students.rename(existing, "Переименован")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> students.delete(existing)).isInstanceOf(AccessDeniedException.class);

        assertThat(repository.findById(alice.id(), existing)).as("Ученик остался на месте").isPresent();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
