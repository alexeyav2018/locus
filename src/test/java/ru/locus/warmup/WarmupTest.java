package ru.locus.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.problem.ProblemId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Задача 1.2: запись Разминки отказывает в неполной и повторяющейся,
 * а не чинит молча; списки после заведения не меняются снаружи.
 */
class WarmupTest {

    private static final WarmupId ID = new WarmupId(1);
    private static final UserId OWNER = new UserId(1);
    private static final LocalDate HELD = LocalDate.of(2026, 10, 20);
    private static final StudentId PETR = new StudentId(1);
    private static final ProblemId FIRST = new ProblemId(1);

    @Test
    void warmupForAStudentAndForAGroup() {
        assertThat(new Warmup(ID, OWNER, HELD, null, List.of(PETR), List.of(FIRST)).isForGroup()).isFalse();
        Warmup forGroup = new Warmup(ID, OWNER, HELD, "9Б", List.of(PETR), List.of(FIRST));
        assertThat(forGroup.isForGroup()).isTrue();
        assertThat(forGroup.hasStudent(PETR)).isTrue();
        assertThat(forGroup.hasStudent(new StudentId(2))).isFalse();
    }

    @Test
    void requiredPartsAreRequired() {
        assertThatThrownBy(() -> new Warmup(ID, null, HELD, null, List.of(PETR), List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Warmup(ID, OWNER, null, null, List.of(PETR), List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Warmup(ID, OWNER, HELD, " ", List.of(PETR), List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Warmup(ID, OWNER, HELD, null, List.of(), List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Warmup(ID, OWNER, HELD, null, List.of(PETR), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatsAreRefused() {
        assertThatThrownBy(() -> new Warmup(ID, OWNER, HELD, null, List.of(PETR, PETR), List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Warmup(ID, OWNER, HELD, null, List.of(PETR), List.of(FIRST, FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAreCopied() {
        List<ProblemId> problems = new ArrayList<>(List.of(FIRST));
        Warmup warmup = new Warmup(ID, OWNER, HELD, null, List.of(PETR), problems);

        problems.add(new ProblemId(2));

        assertThat(warmup.problems()).containsExactly(FIRST);
    }

    @Test
    void identifierIsPositive() {
        assertThatThrownBy(() -> new WarmupId(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
