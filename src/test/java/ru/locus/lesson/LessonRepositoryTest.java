package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentId;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 1.2: хранение Занятий — правило читается обратно, отбор под неделю
 * верен на краях, а каждый метод отвечает только своему владельцу.
 *
 * <p>Изоляция — двумя владельцами на каждом методе: форму «у метода есть
 * {@link UserId}» стережёт {@code OwnerIsRequiredByLessonsTest}, а здесь —
 * что владелец действительно стоит в запросе. Половина правил живёт в схеме
 * (составные ключи, каскад с Группой, проверочные ограничения), поэтому
 * проверка идёт на настоящей базе.
 */
class LessonRepositoryTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private StudentRepository students;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcClient database;

    private UserId alice;
    private UserId bob;
    private StudentId alicesStudent;

    @BeforeEach
    void twoTeachers() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        alicesStudent = students.create(alice, unique("Иванов Пётр"));
    }

    @Test
    void weeklyLessonWithStudentIsReadBackWithAddressee() {
        LessonTiming timing = LessonTiming.weekly(TUESDAY, TUESDAY.plusWeeks(5), FIVE_PM, 60);

        LessonId id = lessons.create(alice, alicesStudent, timing);

        ListedLesson found = lessons.findById(alice, id).orElseThrow();
        assertThat(found.lesson().owner()).isEqualTo(alice);
        assertThat(found.lesson().student()).isEqualTo(alicesStudent);
        assertThat(found.lesson().group()).isNull();
        assertThat(found.lesson().timing()).isEqualTo(timing);
        assertThat(found.addresseeName()).startsWith("Иванов Пётр");
        assertThat(found.withdrawn()).isFalse();
    }

    @Test
    void onceLessonWithGroupIsReadBackWithGroupName() {
        GroupId group = groups.create(alice, unique("9Б"));

        LessonId id = lessons.create(alice, group, LessonTiming.once(TUESDAY, FIVE_PM, 90));

        ListedLesson found = lessons.findById(alice, id).orElseThrow();
        assertThat(found.lesson().group()).isEqualTo(group);
        assertThat(found.lesson().student()).isNull();
        assertThat(found.lesson().timing().weekly()).isFalse();
        assertThat(found.addresseeName()).startsWith("9Б");
    }

    @Test
    void withdrawnStudentIsMarked() {
        LessonId id = lessons.create(alice, alicesStudent, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        students.setWithdrawn(alice, alicesStudent, true);

        assertThat(lessons.findById(alice, id).orElseThrow().withdrawn()).isTrue();
    }

    @Test
    void anotherOwnerDoesNotFindTheLesson() {
        LessonId id = lessons.create(alice, alicesStudent, LessonTiming.once(TUESDAY, FIVE_PM, 60));

        assertThat(lessons.findById(bob, id)).as("чужое Занятие неотличимо от несуществующего").isEmpty();
        assertThat(lessons.findCandidates(bob, TUESDAY, TUESDAY)).extracting(l -> l.lesson().id())
                .doesNotContain(id);
    }

    @Test
    void anotherOwnerNeitherUpdatesNorDeletes() {
        LessonTiming timing = LessonTiming.once(TUESDAY, FIVE_PM, 60);
        LessonId id = lessons.create(alice, alicesStudent, timing);

        lessons.update(bob, id, LessonTiming.once(TUESDAY, LocalTime.NOON, 30));
        lessons.delete(bob, id);

        assertThat(lessons.findById(alice, id).orElseThrow().lesson().timing()).isEqualTo(timing);
    }

    @Test
    void ownerUpdatesAndDeletes() {
        LessonId id = lessons.create(alice, alicesStudent, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LessonTiming changed = LessonTiming.weekly(TUESDAY, TUESDAY.plusWeeks(2), LocalTime.NOON, 45);

        lessons.update(alice, id, changed);
        assertThat(lessons.findById(alice, id).orElseThrow().lesson().timing()).isEqualTo(changed);

        lessons.delete(alice, id);
        assertThat(lessons.findById(alice, id)).isEmpty();
    }

    @Test
    void candidatesCoverTheEdgesOfThePeriod() {
        LocalDate monday = LocalDate.of(2026, 10, 12);
        LocalDate sunday = monday.plusDays(6);
        LessonId onceInside = lessons.create(alice, alicesStudent, LessonTiming.once(sunday, FIVE_PM, 60));
        LessonId onceBefore = lessons.create(alice, alicesStudent, LessonTiming.once(monday.minusDays(1), FIVE_PM, 60));
        LessonId onceAfter = lessons.create(alice, alicesStudent, LessonTiming.once(sunday.plusDays(1), FIVE_PM, 60));
        LessonId endsOnMonday = lessons.create(alice, alicesStudent,
                LessonTiming.weekly(TUESDAY, monday, FIVE_PM, 60));
        LessonId endedBefore = lessons.create(alice, alicesStudent,
                LessonTiming.weekly(TUESDAY, monday.minusDays(1), FIVE_PM, 60));
        LessonId endless = lessons.create(alice, alicesStudent, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LessonId startsAfter = lessons.create(alice, alicesStudent,
                LessonTiming.weekly(sunday.plusDays(1), null, FIVE_PM, 60));

        assertThat(lessons.findCandidates(alice, monday, sunday)).extracting(l -> l.lesson().id())
                .contains(onceInside, endsOnMonday, endless)
                .doesNotContain(onceBefore, onceAfter, endedBefore, startsAfter);
    }

    @Test
    void candidatesComeInOrderOfStartTime() {
        LessonId late = lessons.create(alice, alicesStudent, LessonTiming.once(TUESDAY, FIVE_PM, 60));
        LessonId early = lessons.create(alice, alicesStudent, LessonTiming.once(TUESDAY, LocalTime.of(9, 0), 60));

        assertThat(lessons.findCandidates(alice, TUESDAY, TUESDAY)).extracting(l -> l.lesson().id())
                .containsSubsequence(early, late);
    }

    @Test
    void foreignStudentIsRefusedByTheDatabase() {
        StudentId bobsStudent = students.create(bob, unique("Сидорова Анна"));

        assertThatThrownBy(() -> lessons.create(alice, bobsStudent, LessonTiming.once(TUESDAY, FIVE_PM, 60)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignGroupIsRefusedByTheDatabase() {
        GroupId bobsGroup = groups.create(bob, unique("10А"));

        assertThatThrownBy(() -> lessons.create(alice, bobsGroup, LessonTiming.once(TUESDAY, FIVE_PM, 60)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lessonsLeaveTogetherWithTheGroup() {
        GroupId group = groups.create(alice, unique("9Б"));
        LessonId ofGroup = lessons.create(alice, group, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        LessonId ofStudent = lessons.create(alice, alicesStudent, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));

        groups.delete(alice, group);

        assertThat(lessons.findById(alice, ofGroup)).as("Занятия Группы уходят с ней (ADR-0047)").isEmpty();
        assertThat(lessons.findById(alice, ofStudent)).isPresent();
    }

    @Test
    void studentWithLessonIsNotDeletedByTheDatabase() {
        lessons.create(alice, alicesStudent, LessonTiming.once(TUESDAY, FIVE_PM, 60));

        assertThatThrownBy(() -> students.delete(alice, alicesStudent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lessonsAreCountedByStudentAndOwner() {
        lessons.create(alice, alicesStudent, LessonTiming.once(TUESDAY, FIVE_PM, 60));
        lessons.create(alice, alicesStudent, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));

        assertThat(lessons.countByStudent(alice, alicesStudent)).isEqualTo(2);
        assertThat(lessons.countByStudent(bob, alicesStudent)).isZero();
    }

    @Test
    void schemaRefusesBrokenRules() {
        assertThatThrownBy(() -> database.sql("""
                        insert into lesson (user_id, student_id, group_id, first_date, weekly, start_time, duration_minutes)
                        values (?, null, null, ?, false, ?, 60)
                        """).params(alice.value(), TUESDAY, FIVE_PM).update())
                .as("без адресата").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> database.sql("""
                        insert into lesson (user_id, student_id, first_date, weekly, start_time, duration_minutes)
                        values (?, ?, ?, false, ?, 0)
                        """).params(alice.value(), alicesStudent.value(), TUESDAY, FIVE_PM).update())
                .as("нулевая длительность").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> database.sql("""
                        insert into lesson (user_id, student_id, first_date, last_date, weekly, start_time, duration_minutes)
                        values (?, ?, ?, ?, false, ?, 60)
                        """).params(alice.value(), alicesStudent.value(), TUESDAY, TUESDAY, FIVE_PM).update())
                .as("последняя дата у разового").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> database.sql("""
                        insert into lesson (user_id, student_id, first_date, last_date, weekly, start_time, duration_minutes)
                        values (?, ?, ?, ?, true, ?, 60)
                        """).params(alice.value(), alicesStudent.value(), TUESDAY, TUESDAY.minusDays(1), FIVE_PM)
                .update())
                .as("последняя дата раньше первой").isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String unique(String name) {
        return name + " " + UUID.randomUUID();
    }
}
