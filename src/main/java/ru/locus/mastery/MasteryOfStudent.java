package ru.locus.mastery;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.student.StudentId;
import ru.locus.student.StudentUsage;
import ru.locus.user.CurrentUser;

/**
 * Ответ отметок Владения на вопрос «ссылается ли что-нибудь на Ученика»:
 * Ученик, о котором вынесено хотя бы одно суждение, не удаляется (ADR-0035).
 *
 * <p>Считаются суждения, а не ячейки: «неизвестно» строки не имеет
 * (ADR-0039), и Ученик, у которого все ячейки неизвестны, отметками
 * не держится. Этим третий и последний ответчик на {@link StudentUsage}
 * на месте: отказ в удалении называет Задания, Работы и суждения разом —
 * {@code StudentService} собирает ответы всех.
 *
 * <p>Владелец — у {@link CurrentUser}, как у {@code WorksOfStudent};
 * зависит только от репозитория, не от {@code MasteryService}: тот
 * не нужен здесь по существу, а сервисы, стоящие за ним, ведут
 * к {@code StudentService}, который собирает список ответчиков.
 */
@Component
public class MasteryOfStudent implements StudentUsage {

    private final MasteryRepository marks;
    private final CurrentUser currentUser;

    public MasteryOfStudent(MasteryRepository marks, CurrentUser currentUser) {
        this.marks = marks;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<String> of(StudentId student) {
        int count = marks.countByStudent(currentUser.id(), student);
        return count == 0 ? Optional.empty() : Optional.of("вынесено суждений (" + count + ")");
    }
}
