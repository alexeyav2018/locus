package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 5.2: хранение Групп и их состава — запись читается обратно,
 * имя ищется без учёта регистра, состав задаётся целиком, а главное —
 * каждый метод отвечает только своему владельцу.
 *
 * Как и в {@link StudentRepositoryTest}, изоляция проверяется на КАЖДОМ
 * методе двумя владельцами. Сверх того здесь проверяется то, что держит
 * схема, а не Java: чужой Ученик в составе не вставляется — составному
 * ключу не на что сослаться; удаление Группы уносит её список, а Ученики
 * остаются; удаление Ученика вычёркивает его из Групп.
 */
class GroupRepositoryTest extends IntegrationTest {

    @Autowired
    private GroupRepository groups;

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
    void createdGroupIsReadBackByItsOwner() {
        String name = unique("9Б");

        GroupId id = groups.create(alice, name);

        Group found = groups.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.owner()).isEqualTo(alice);
        assertThat(found.name()).isEqualTo(name);
    }

    @Test
    void anotherOwnerDoesNotFindTheGroup() {
        GroupId id = groups.create(alice, unique("9Б"));

        assertThat(groups.findById(bob, id))
                .as("чужая Группа неотличима от несуществующей")
                .isEmpty();
    }

    @Test
    void listHoldsOnlyOwnGroups() {
        GroupId mine = groups.create(alice, unique("9Б"));
        GroupId theirs = groups.create(bob, unique("9Б"));

        assertThat(groups.findAll(alice)).extracting(Group::id).contains(mine).doesNotContain(theirs);
        assertThat(groups.findAll(bob)).extracting(Group::id).contains(theirs).doesNotContain(mine);
    }

    @Test
    void groupsComeInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        groups.create(alice, "cc-" + mark);
        groups.create(alice, "aa-" + mark);
        groups.create(alice, "bb-" + mark);

        List<String> mine = groups.findAll(alice).stream()
                .map(Group::name)
                .filter(name -> name.endsWith(mark))
                .toList();

        assertThat(mine).containsExactly("aa-" + mark, "bb-" + mark, "cc-" + mark);
    }

    /** Поиск по имени сравнивает так же, как индекс: без учёта регистра. */
    @Test
    void nameIsFoundRegardlessOfCase() {
        String name = unique("Группа Б");
        GroupId id = groups.create(alice, name);

        assertThat(groups.findByName(alice, name.toUpperCase()).map(Group::id)).contains(id);
        assertThat(groups.findByName(alice, name.toLowerCase()).map(Group::id)).contains(id);
        assertThat(groups.findByName(alice, unique("другое"))).isEmpty();
    }

    @Test
    void anotherOwnerDoesNotFindTheGroupByName() {
        String name = unique("9Б");
        groups.create(alice, name);

        assertThat(groups.findByName(bob, name)).isEmpty();
    }

    /** Имя уникально у одного владельца без учёта регистра — это держит индекс. */
    @Test
    void sameNameTwiceForOneOwnerIsRefusedByTheIndex() {
        String name = unique("9Б");
        groups.create(alice, name);

        assertThatThrownBy(() -> groups.create(alice, name.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** У разных владельцев имена не пересекаются: {@code user_id} в индексе. */
    @Test
    void twoOwnersMayUseTheSameName() {
        String name = unique("9Б");

        GroupId mine = groups.create(alice, name);
        GroupId theirs = groups.create(bob, name);

        assertThat(mine).isNotEqualTo(theirs);
        assertThat(groups.findByName(alice, name).map(Group::id)).contains(mine);
        assertThat(groups.findByName(bob, name).map(Group::id)).contains(theirs);
    }

    @Test
    void renamedGroupKeepsItsIdentifier() {
        GroupId id = groups.create(alice, unique("9Б"));
        String renamed = unique("10Б");

        groups.rename(alice, id, renamed);

        Group found = groups.findById(alice, id).orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.name()).isEqualTo(renamed);
    }

    @Test
    void anotherOwnerCannotRenameTheGroup() {
        String name = unique("9Б");
        GroupId id = groups.create(alice, name);

        groups.rename(bob, id, unique("Чужое имя"));

        assertThat(groups.findById(alice, id).orElseThrow().name())
                .as("правка чужой Группы не меняет ни одной строки")
                .isEqualTo(name);
    }

    @Test
    void deletedGroupDisappears() {
        GroupId gone = groups.create(alice, unique("9Б"));
        GroupId kept = groups.create(alice, unique("10Б"));

        groups.delete(alice, gone);

        assertThat(groups.findById(alice, gone)).isEmpty();
        assertThat(groups.findAll(alice)).extracting(Group::id).doesNotContain(gone).contains(kept);
    }

    @Test
    void anotherOwnerCannotDeleteTheGroup() {
        GroupId id = groups.create(alice, unique("9Б"));

        groups.delete(bob, id);

        assertThat(groups.findById(alice, id))
                .as("удаление чужой Группы не меняет ни одной строки")
                .isPresent();
    }

    @Test
    void membersAreSetAsAWholeAndReadBackInAlphabeticalOrder() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId ivanov = students.create(alice, "Иванов " + UUID.randomUUID());
        StudentId sidorova = students.create(alice, "Сидорова " + UUID.randomUUID());
        StudentId antonov = students.create(alice, "Антонов " + UUID.randomUUID());

        groups.setMembers(alice, group, List.of(sidorova, ivanov));
        assertThat(groups.members(alice, group)).containsExactly(ivanov, sidorova);

        groups.setMembers(alice, group, List.of(antonov, ivanov));
        assertThat(groups.members(alice, group))
                .as("состав задаётся целиком: прежний список снят, новый вставлен")
                .containsExactly(antonov, ivanov);

        groups.setMembers(alice, group, List.of());
        assertThat(groups.members(alice, group)).isEmpty();
    }

    /** Состав — множество: повтор в списке не роняет первичный ключ и не даёт второй строки. */
    @Test
    void repeatedStudentIsWrittenOnce() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId ivanov = students.create(alice, unique("Иванов Пётр"));

        groups.setMembers(alice, group, List.of(ivanov, ivanov));

        assertThat(groups.members(alice, group)).containsExactly(ivanov);
    }

    @Test
    void anotherOwnerDoesNotSeeTheMembers() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId ivanov = students.create(alice, unique("Иванов Пётр"));
        groups.setMembers(alice, group, List.of(ivanov));

        assertThat(groups.members(bob, group)).isEmpty();
    }

    /**
     * Чужой Ученик в составе не вставляется: строке {@code group_student}
     * с владельцем А и Учеником Б не на что сослаться по ключу
     * {@code fk_group_student_student}. Инвариант держит схема, а не сервис.
     */
    @Test
    void studentOfAnotherOwnerCannotBecomeAMember() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId mine = students.create(alice, unique("Иванов Пётр"));
        StudentId theirs = students.create(bob, unique("Сидорова Анна"));
        groups.setMembers(alice, group, List.of(mine));

        assertThatThrownBy(() -> groups.setMembers(alice, group, List.of(theirs)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(groups.members(alice, group))
                .as("строки с чужим Учеником нет: база её не приняла")
                .doesNotContain(theirs);
    }

    /** Чужая Группа не получает состава: ключ {@code fk_group_student_group} не найдёт пары (id, владелец). */
    @Test
    void anotherOwnerCannotChangeTheMembers() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId mine = students.create(alice, unique("Иванов Пётр"));
        StudentId theirs = students.create(bob, unique("Сидорова Анна"));
        groups.setMembers(alice, group, List.of(mine));

        groups.setMembers(bob, group, List.of());
        assertThat(groups.members(alice, group))
                .as("пустой состав от чужого владельца не снимает ни одной строки")
                .containsExactly(mine);

        assertThatThrownBy(() -> groups.setMembers(bob, group, List.of(theirs)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(groups.members(alice, group)).containsExactly(mine);
    }

    @Test
    void deletingTheGroupRemovesTheMembershipButKeepsTheStudents() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId ivanov = students.create(alice, unique("Иванов Пётр"));
        groups.setMembers(alice, group, List.of(ivanov));

        groups.delete(alice, group);

        assertThat(groups.groupsOf(alice, ivanov)).extracting(Group::id).doesNotContain(group);
        assertThat(students.findById(alice, ivanov)).as("Ученик остаётся: Группа — список, не суждение").isPresent();
    }

    @Test
    void deletingTheStudentStrikesHimOutOfTheGroups() {
        GroupId first = groups.create(alice, unique("9Б"));
        GroupId second = groups.create(alice, unique("Кружок"));
        StudentId gone = students.create(alice, unique("Иванов Пётр"));
        StudentId kept = students.create(alice, unique("Сидорова Анна"));
        groups.setMembers(alice, first, List.of(gone, kept));
        groups.setMembers(alice, second, List.of(gone));

        students.delete(alice, gone);

        assertThat(groups.members(alice, first)).containsExactly(kept);
        assertThat(groups.members(alice, second)).isEmpty();
        assertThat(groups.findById(alice, second)).as("Группа переживает опустевший состав").isPresent();
    }

    @Test
    void groupsOfAStudentComeInAlphabeticalOrder() {
        String mark = UUID.randomUUID().toString();
        GroupId circle = groups.create(alice, "Кружок-" + mark);
        GroupId klass = groups.create(alice, "9Б-" + mark);
        GroupId other = groups.create(alice, "10А-" + mark);
        StudentId ivanov = students.create(alice, unique("Иванов Пётр"));
        groups.setMembers(alice, circle, List.of(ivanov));
        groups.setMembers(alice, klass, List.of(ivanov));
        groups.setMembers(alice, other, List.of());

        assertThat(groups.groupsOf(alice, ivanov))
                .extracting(Group::id)
                .as("«9Б» стоит раньше «Кружка», а Группа без этого Ученика не показывается вовсе")
                .containsExactly(klass, circle);
    }

    @Test
    void anotherOwnerDoesNotSeeTheGroupsOfTheStudent() {
        GroupId group = groups.create(alice, unique("9Б"));
        StudentId ivanov = students.create(alice, unique("Иванов Пётр"));
        groups.setMembers(alice, group, List.of(ivanov));

        assertThat(groups.groupsOf(bob, ivanov)).isEmpty();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
