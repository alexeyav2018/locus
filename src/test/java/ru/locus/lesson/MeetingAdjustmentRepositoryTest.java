package ru.locus.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.TestAccounts;
import ru.locus.lesson.MeetingAdjustment.Move;
import ru.locus.student.GroupId;
import ru.locus.student.GroupRepository;
import ru.locus.student.StudentRepository;
import ru.locus.user.Role;
import ru.locus.user.UserId;

/**
 * Задача 1.3: хранение Поправок Встреч (ADR-0048) — поправка читается
 * обратно и заменяется на своей паре, отбор под неделю видит перенос
 * с обеих сторон, каскады идут с Занятием и Группой, а каждый метод
 * отвечает только своему владельцу. Половина правил живёт в схеме,
 * поэтому проверка — на настоящей базе.
 */
class MeetingAdjustmentRepositoryTest extends IntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);
    private static final Move TO_NEXT_MONDAY = new Move(LocalDate.of(2026, 10, 12), LocalTime.of(18, 0), 60);

    @Autowired
    private MeetingAdjustmentRepository adjustments;

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
    private LessonId alicesLesson;

    @BeforeEach
    void twoTeachers() {
        alice = accounts.settled(Role.TEACHER).id();
        bob = accounts.settled(Role.TEACHER).id();
        alicesLesson = lessons.create(alice, students.create(alice, unique("Иванов Пётр")),
                LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
    }

    @Test
    void adjustmentIsReadBackAndReplacedOnItsPair() {
        MeetingAdjustment moved = MeetingAdjustment.none(alicesLesson, TUESDAY).withMove(TO_NEXT_MONDAY);
        adjustments.save(alice, moved);
        assertThat(adjustments.find(alice, alicesLesson, TUESDAY)).contains(moved);

        MeetingAdjustment cancelled = moved.withCancelled(true);
        adjustments.save(alice, cancelled);

        assertThat(adjustments.find(alice, alicesLesson, TUESDAY)).contains(cancelled);
        assertThat(adjustments.findByLesson(alice, alicesLesson)).containsExactly(cancelled);
    }

    @Test
    void weekFindsBothThePlannedAndTheMovedSide() {
        MeetingAdjustment moved = MeetingAdjustment.none(alicesLesson, TUESDAY).withMove(TO_NEXT_MONDAY);
        adjustments.save(alice, moved);
        List<LessonId> ids = List.of(alicesLesson);

        assertThat(adjustments.findForLessons(alice, ids, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)))
                .containsExactly(moved);
        assertThat(adjustments.findForLessons(alice, ids, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)))
                .containsExactly(moved);
        assertThat(adjustments.findForLessons(alice, ids, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)))
                .isEmpty();
        assertThat(adjustments.findForLessons(alice, List.of(), TUESDAY, TUESDAY)).isEmpty();
    }

    @Test
    void emptyAdjustmentIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> adjustments.save(alice, MeetingAdjustment.none(alicesLesson, TUESDAY)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anotherOwnersLessonIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> adjustments.save(bob, MeetingAdjustment.none(alicesLesson, TUESDAY)
                .withCancelled(true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anotherOwnerSeesAndRemovesNothing() {
        MeetingAdjustment cancelled = MeetingAdjustment.none(alicesLesson, TUESDAY).withCancelled(true);
        adjustments.save(alice, cancelled);

        assertThat(adjustments.find(bob, alicesLesson, TUESDAY)).isEmpty();
        assertThat(adjustments.findByLesson(bob, alicesLesson)).isEmpty();
        assertThat(adjustments.findForLessons(bob, List.of(alicesLesson), TUESDAY, TUESDAY)).isEmpty();
        adjustments.delete(bob, alicesLesson, TUESDAY);
        adjustments.deleteDates(bob, alicesLesson, List.of(TUESDAY));
        adjustments.rehome(bob, alicesLesson, alicesLesson, TUESDAY);

        assertThat(adjustments.find(alice, alicesLesson, TUESDAY)).contains(cancelled);
    }

    @Test
    void deleteAndDeleteDatesRemoveOnlyTheNamedPairs() {
        LocalDate next = TUESDAY.plusWeeks(1);
        LocalDate later = TUESDAY.plusWeeks(2);
        for (LocalDate date : List.of(TUESDAY, next, later)) {
            adjustments.save(alice, MeetingAdjustment.none(alicesLesson, date).withCancelled(true));
        }

        adjustments.delete(alice, alicesLesson, TUESDAY);
        adjustments.deleteDates(alice, alicesLesson, List.of(later));
        adjustments.deleteDates(alice, alicesLesson, List.of());

        assertThat(adjustments.findByLesson(alice, alicesLesson)).extracting(MeetingAdjustment::plannedDate)
                .containsExactly(next);
    }

    @Test
    void rehomeMovesAdjustmentsFromTheDateToTheOtherPart() {
        LessonId continuation = lessons.create(alice, lessons.findById(alice, alicesLesson).orElseThrow()
                .lesson().student(), LessonTiming.weekly(TUESDAY.plusWeeks(2), null, FIVE_PM, 90));
        adjustments.save(alice, MeetingAdjustment.none(alicesLesson, TUESDAY).withCancelled(true));
        adjustments.save(alice, MeetingAdjustment.none(alicesLesson, TUESDAY.plusWeeks(2)).withCancelled(true));

        adjustments.rehome(alice, alicesLesson, continuation, TUESDAY.plusWeeks(2));

        assertThat(adjustments.findByLesson(alice, alicesLesson)).extracting(MeetingAdjustment::plannedDate)
                .containsExactly(TUESDAY);
        assertThat(adjustments.findByLesson(alice, continuation)).extracting(MeetingAdjustment::plannedDate)
                .containsExactly(TUESDAY.plusWeeks(2));
    }

    @Test
    void adjustmentsGoAwayWithTheirLesson() {
        adjustments.save(alice, MeetingAdjustment.none(alicesLesson, TUESDAY).withAbsent(true));

        lessons.delete(alice, alicesLesson);

        assertThat(rowsOf(alicesLesson)).isZero();
    }

    @Test
    void adjustmentsGoAwayWithTheGroupOfTheirLesson() {
        GroupId group = groups.create(alice, unique("9Б"));
        LessonId groupLesson = lessons.create(alice, group, LessonTiming.weekly(TUESDAY, null, FIVE_PM, 60));
        adjustments.save(alice, MeetingAdjustment.none(groupLesson, TUESDAY).withCancelled(true));

        groups.delete(alice, group);

        assertThat(rowsOf(groupLesson)).isZero();
    }

    private int rowsOf(LessonId lesson) {
        return database.sql("select count(*) from meeting_adjustment where lesson_id = ?")
                .param(lesson.value())
                .query(Integer.class)
                .single();
    }

    private static String unique(String name) {
        return name + " " + UUID.randomUUID();
    }
}
