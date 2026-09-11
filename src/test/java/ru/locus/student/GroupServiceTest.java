package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
 * Задача 6.1: правила Групп — заведение, занятое имя, состав, удаление,
 * роль и владелец.
 *
 * Как и в {@link StudentServiceTest}, правила проверяются на сервисе,
 * а не через экран, и вошедший — настоящая учётная запись: сервису
 * личного контура нужен {@code UserId} владельца.
 *
 * Чужое здесь заводится напрямую через репозитории от имени второго
 * Учителя: сервис от чужого лица не вызывается, и «чужое» в тестах —
 * это строки с другим {@code user_id}.
 */
class GroupServiceTest extends IntegrationTest {

    @Autowired
    private GroupService groups;

    @Autowired
    private StudentService students;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account alice;
    private TestAccounts.Account bob;

    @BeforeEach
    void logInAsATeacher() {
        alice = accounts.settled(Role.TEACHER);
        bob = accounts.settled(Role.TEACHER);
        LoggedIn.as(alice);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Заведение Группы». */
    @Test
    void createdGroupIsEmptyBelongsToTheLoggedInTeacherAndAppearsInTheList() {
        String name = unique("9Б");

        GroupId id = groups.create(name);

        Group group = groups.group(id);
        assertThat(group.name()).isEqualTo(name);
        assertThat(group.owner()).as("владелец подставлен из вошедшего, не из параметра").isEqualTo(alice.id());
        assertThat(groups.members(id)).isEmpty();
        assertThat(groups.all()).extracting(Group::id).contains(id);
    }

    /** Владелец не принимается из запроса: у заведения нет такого параметра. */
    @Test
    void nothingButANameIsAskedForAtCreation() {
        assertThat(GroupService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("create"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .as("владельца при заведении указать невозможно (ADR-0027)")
                        .containsExactly(String.class));
    }

    @Test
    void emptyNameCreatesNothing() {
        int before = groups.all().size();

        assertThatThrownBy(() -> groups.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустым");
        assertThatThrownBy(() -> groups.create(null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(groups.all()).hasSize(before);
    }

    @Test
    void surroundingSpacesAreTrimmed() {
        String name = unique("9Б");

        GroupId id = groups.create("   " + name + "   ");

        assertThat(groups.group(id).name()).isEqualTo(name);
    }

    /** Сценарий «Имя Группы занято» — в другом регистре тоже. */
    @Test
    void nameTakenInAnotherCaseIsRefused() {
        String name = unique("9Б");
        groups.create(name);
        int before = groups.all().size();

        assertThatThrownBy(() -> groups.create(name.toLowerCase()))
                .isInstanceOf(NameAlreadyTakenException.class)
                .hasMessageContaining(name.toLowerCase());
        assertThatThrownBy(() -> groups.create(name)).isInstanceOf(NameAlreadyTakenException.class);

        assertThat(groups.all()).hasSize(before);
    }

    /** Сценарий «Одинаковые имена у разных Учителей». */
    @Test
    void anotherTeacherMayUseTheSameName() {
        String name = unique("9Б");
        groupRepository.create(bob.id(), name);

        GroupId mine = groups.create(name);

        assertThat(groups.group(mine).name()).isEqualTo(name);
    }

    @Test
    void renamedGroupKeepsItsIdentifierAndMembers() {
        GroupId id = groups.create(unique("9Б"));
        StudentId member = students.create(unique("Иванов Пётр"));
        groups.setMembers(id, List.of(member));
        String newName = unique("10Б");

        groups.rename(id, newName);

        assertThat(groups.group(id).name()).isEqualTo(newName);
        assertThat(groups.members(id)).extracting(Student::id).containsExactly(member);
    }

    @Test
    void renamingToAnotherGroupsNameIsRefused() {
        String taken = unique("9Б");
        groups.create(taken);
        String own = unique("10Б");
        GroupId id = groups.create(own);

        assertThatThrownBy(() -> groups.rename(id, taken.toUpperCase())).isInstanceOf(NameAlreadyTakenException.class);
        assertThatThrownBy(() -> groups.rename(id, " ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(groups.group(id).name()).isEqualTo(own);
    }

    /** Своё имя в другом регистре занятым не считается: запись не мешает самой себе. */
    @Test
    void renamingToOwnNameInAnotherCaseIsAllowed() {
        String name = unique("9б");
        GroupId id = groups.create(name);

        groups.rename(id, name.toUpperCase());

        assertThat(groups.group(id).name()).isEqualTo(name.toUpperCase());
    }

    /** Сценарии «Состав Группы» и «Ученик в нескольких Группах». */
    @Test
    void membersAreSetAsAWholeAndAStudentMayBeInSeveralGroups() {
        StudentId first = students.create(unique("Иванов Пётр"));
        StudentId second = students.create(unique("Сидорова Анна"));
        StudentId third = students.create(unique("Кузнецов Олег"));
        GroupId one = groups.create(unique("9Б"));
        GroupId other = groups.create(unique("Кружок"));

        groups.setMembers(one, List.of(first, second));
        groups.setMembers(other, List.of(second, third));
        groups.setMembers(one, List.of(first, third));

        assertThat(groups.members(one)).extracting(Student::id).containsExactlyInAnyOrder(first, third);
        assertThat(groups.members(other)).extracting(Student::id).containsExactlyInAnyOrder(second, third);
        assertThat(groups.groupsOf(third)).extracting(Group::id).containsExactlyInAnyOrder(one, other);
        assertThat(groups.groupsOf(second)).extracting(Group::id).containsExactly(other);

        groups.setMembers(one, List.of());

        assertThat(groups.members(one)).isEmpty();
        assertThat(groups.groupsOf(first)).isEmpty();
    }

    /** Сценарий «Чужой Ученик в составе»: отказ как на несуществующем, состав не меняется. */
    @Test
    void anotherTeachersStudentInTheMembersIsRefusedAndTheMembersStay() {
        StudentId own = students.create(unique("Иванов Пётр"));
        StudentId bobs = studentRepository.create(bob.id(), unique("Чужой Ученик"));
        StudentId missing = new StudentId(bobs.value() + 1_000_000);
        GroupId id = groups.create(unique("9Б"));
        groups.setMembers(id, List.of(own));

        assertThatThrownBy(() -> groups.setMembers(id, List.of(own, bobs))).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> groups.setMembers(id, List.of(missing))).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> groups.groupsOf(bobs)).isInstanceOf(StudentNotFoundException.class);

        assertThat(groups.members(id)).extracting(Student::id).containsExactly(own);
        assertThat(groupRepository.groupsOf(bob.id(), bobs)).as("Ученик другого Учителя ни в какую Группу не попал").isEmpty();
    }

    /** Сценарий «Удаление Группы с составом»: список исчез, карточки на месте. */
    @Test
    void deletedGroupLeavesItsStudentsAlone() {
        StudentId member = students.create(unique("Иванов Пётр"));
        GroupId gone = groups.create(unique("9Б"));
        GroupId kept = groups.create(unique("Кружок"));
        groups.setMembers(gone, List.of(member));
        groups.setMembers(kept, List.of(member));

        groups.delete(gone);

        assertThat(groups.all()).extracting(Group::id).doesNotContain(gone).contains(kept);
        assertThatThrownBy(() -> groups.group(gone)).isInstanceOf(GroupNotFoundException.class);
        assertThat(students.student(member).id()).isEqualTo(member);
        assertThat(groups.groupsOf(member)).extracting(Group::id).containsExactly(kept);
    }

    /** Сценарии «Список Групп другого Учителя» и «Правка чужой Группы». */
    @Test
    void anotherTeachersGroupIsIndistinguishableFromAMissingOne() {
        String bobsName = unique("Чужая Группа");
        GroupId bobs = groupRepository.create(bob.id(), bobsName);
        StudentId bobsStudent = studentRepository.create(bob.id(), unique("Чужой Ученик"));
        groupRepository.setMembers(bob.id(), bobs, List.of(bobsStudent));
        GroupId missing = new GroupId(bobs.value() + 1_000_000);
        StudentId own = students.create(unique("Иванов Пётр"));

        for (GroupId id : List.of(bobs, missing)) {
            assertThatThrownBy(() -> groups.group(id)).isInstanceOf(GroupNotFoundException.class);
            assertThatThrownBy(() -> groups.rename(id, "Переименована")).isInstanceOf(GroupNotFoundException.class);
            assertThatThrownBy(() -> groups.delete(id)).isInstanceOf(GroupNotFoundException.class);
            assertThatThrownBy(() -> groups.members(id)).isInstanceOf(GroupNotFoundException.class);
            assertThatThrownBy(() -> groups.setMembers(id, List.of(own))).isInstanceOf(GroupNotFoundException.class);
        }

        assertThat(groups.all()).extracting(Group::id).doesNotContain(bobs);
        Group untouched = groupRepository.findById(bob.id(), bobs).orElseThrow();
        assertThat(untouched.name()).as("Группа другого Учителя осталась прежней").isEqualTo(bobsName);
        assertThat(groupRepository.members(bob.id(), bobs)).as("и состав её прежний").containsExactly(bobsStudent);
    }

    /** Сценарий «Администратор без роли Учителя»: недоступно и чтение. */
    @Test
    void userWithoutTheTeacherRoleIsRefusedEverything() {
        GroupId existing = groups.create(unique("9Б"));
        StudentId student = students.create(unique("Иванов Пётр"));
        LoggedIn.as(accounts.settled(Role.ADMINISTRATOR));

        assertThatThrownBy(groups::all).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.group(existing)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.create("Новая")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.rename(existing, "Переименована")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.delete(existing)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.members(existing)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.setMembers(existing, List.of(student))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> groups.groupsOf(student)).isInstanceOf(AccessDeniedException.class);

        assertThat(groupRepository.findById(alice.id(), existing)).as("Группа осталась на месте").isPresent();
        assertThat(groupRepository.members(alice.id(), existing)).isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
