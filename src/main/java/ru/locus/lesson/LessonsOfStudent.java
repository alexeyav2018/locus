package ru.locus.lesson;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.student.StudentId;
import ru.locus.student.StudentUsage;
import ru.locus.user.CurrentUser;

/**
 * Ответ Расписания на вопрос «ссылается ли что-нибудь на Ученика»: Ученик,
 * которому назначено Занятие, не удаляется (ADR-0035, ADR-0047).
 *
 * <p>Считаются только Занятия, назначенные Ученику лично; Занятия его Групп
 * на Ученика не ссылаются — они у Группы и уходят вместе с ней. Отказ
 * базы при удалении Ученика с Занятием (составной ключ без каскада)
 * остаётся страховкой, а учителю причину называет этот ответ.
 *
 * <p>Владелец — у {@link CurrentUser}, как у
 * {@link ru.locus.work.WorksOfStudent}; зависит только от репозитория
 * и {@link CurrentUser}, не от {@link LessonService}: тот зависит
 * от {@code StudentService}, который собирает список ответчиков.
 */
@Component
public class LessonsOfStudent implements StudentUsage {

    private final LessonRepository lessons;
    private final CurrentUser currentUser;

    public LessonsOfStudent(LessonRepository lessons, CurrentUser currentUser) {
        this.lessons = lessons;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<String> of(StudentId student) {
        int count = lessons.countByStudent(currentUser.id(), student);
        return count == 0 ? Optional.empty() : Optional.of("назначено Занятий (" + count + ")");
    }
}
