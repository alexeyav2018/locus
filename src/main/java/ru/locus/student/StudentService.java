package ru.locus.student;

import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Ведение Учеников: чтение своих карточек и правила их заведения, правки
 * и удаления.
 *
 * Права проверяются здесь, а не в контроллере и не на адресах (standards.md,
 * «Слои и границы»): правило привязано к операции и срабатывает при любом
 * способе вызова. В отличие от библиотеки, <b>и чтение только Учителю</b>:
 * у Пользователя без этой роли Учеников нет, и список для него не пуст,
 * а недоступен — пустой список притворялся бы, что владелец есть.
 *
 * Ученики — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027). Владельца сервис берёт у {@link CurrentUser} в начале каждой
 * операции и передаёт в репозиторий, у которого без владельца нет ни одного
 * метода; из запроса владелец не читается никогда — контроллер о нём
 * не знает. Чужая запись и несуществующая для сервиса неразличимы:
 * репозиторий отвечает пусто в обоих случаях, и наружу уходит один и тот же
 * {@link StudentNotFoundException}.
 */
@Service
public class StudentService {

    private final StudentRepository students;
    private final CurrentUser currentUser;

    /**
     * Реализации вопроса «ссылается ли что-нибудь на Ученика». Сегодня
     * список пуст — ни Заданий, ни Работ, ни отметок не существует;
     * подробности и долг — в {@link StudentUsage}.
     */
    private final List<StudentUsage> usages;

    public StudentService(StudentRepository students, CurrentUser currentUser, List<StudentUsage> usages) {
        this.students = students;
        this.currentUser = currentUser;
        this.usages = usages;
    }

    /** Все Ученики вошедшего Учителя, по алфавиту. */
    @PreAuthorize("hasRole('TEACHER')")
    public List<Student> all() {
        return students.findAll(owner());
    }

    /** Ученик вошедшего Учителя; чужой или несуществующий — {@link StudentNotFoundException}. */
    @PreAuthorize("hasRole('TEACHER')")
    public Student student(StudentId id) {
        return existing(owner(), id);
    }

    /**
     * Заводит Ученика у вошедшего Учителя. Имя обязательно и не пустое;
     * занятость не проверяется — однофамильцы допустимы.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public StudentId create(String name) {
        return students.create(owner(), requireName(name));
    }

    /**
     * Меняет имя Ученика. Идентификатор при этом не меняется, и всё,
     * что на Ученика ссылается, ссылается по-прежнему — поэтому
     * переименование свободно всегда (ADR-0035).
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void rename(StudentId id, String newName) {
        UserId owner = owner();
        Student student = existing(owner, id);
        students.rename(owner, student.id(), requireName(newName));
    }

    /** Удаляет Ученика, если на него ничего не ссылается. */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void delete(StudentId id) {
        UserId owner = owner();
        Student student = existing(owner, id);
        refuseUnlessUnused(student);
        students.delete(owner, student.id());
    }

    /**
     * Единственная проверка того, что на Ученика ничего не ссылается: сюда
     * дописывается каждое новое условие, и искать его потом надо в одном
     * месте, а не по всем вызовам удаления.
     *
     * <p>Условие спрашивается у {@link StudentUsage} — вопроса, на который
     * отвечают области, ссылающиеся на Ученика; сам сервис ни одну из них
     * не знает по имени. Сегодня реализаций нет, условие истинно
     * тождественно, и удаление проходит всегда.
     *
     * <p><b>Появление каждой из трёх сущностей обязано пополнить эту
     * проверку</b> своей реализацией {@link StudentUsage}:
     *
     * <ul>
     *   <li>Задание — работа {@code assignments};</li>
     *   <li>Работа — работа {@code submission-review};</li>
     *   <li>отметка Владения — работа {@code mastery-marks}.</li>
     * </ul>
     *
     * <p>Забытое пополнение — тихая потеря данных: удаление Ученика с историей
     * уносит месяцы суждений о человеке, и восстановить их неоткуда
     * (ADR-0035). Что делать с учеником, который занимался и перестал, —
     * выбытие, — этим правилом не решается и записано долгом там же.
     */
    private void refuseUnlessUnused(Student student) {
        for (StudentUsage usage : usages) {
            Optional<String> used = usage.of(student.id());
            if (used.isPresent()) {
                throw new StudentInUseException("На Ученика «" + student.name() + "» ссылается: "
                        + used.get() + ". Пока это так, удалить его нельзя");
            }
        }
    }

    private UserId owner() {
        return currentUser.id();
    }

    private Student existing(UserId owner, StudentId id) {
        return students.findById(owner, id).orElseThrow(() -> new StudentNotFoundException(id));
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Имя Ученика не может быть пустым");
        }
        return trimmed;
    }
}
