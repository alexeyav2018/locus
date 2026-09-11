package ru.locus.student;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Ведение Групп: чтение своих Групп, их заведение, правка имени,
 * состав и удаление.
 *
 * Правила и права стоят здесь, а не в контроллере, по тем же причинам,
 * что у {@link StudentService}: правило привязано к операции и срабатывает
 * при любом способе вызова; и чтение — только Учителю.
 *
 * Группы — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027). Владельца сервис берёт у {@link CurrentUser} в начале каждой
 * операции и передаёт в репозиторий; из запроса он не читается никогда.
 * Чужая Группа и несуществующая неразличимы — наружу уходит один
 * {@link GroupNotFoundException}.
 *
 * Два правила, которых нет у Учеников:
 *
 * <ul>
 *   <li>имя Группы уникально у владельца без учёта регистра — проверяется
 *       через {@link GroupRepository#findByName} раньше индекса
 *       {@code uq_group_user_name} и тем же сравнением, чтобы отказ пришёл
 *       с внятным сообщением, а не нарушением ограничения;</li>
 *   <li>в состав попадают только Ученики того же владельца — каждый
 *       идентификатор читается через {@link StudentRepository#findById}
 *       с владельцем, и чужой Ученик отвергается так же, как
 *       несуществующий; схема держит то же составными ключами, но это
 *       последний рубеж, а не способ проверки.</li>
 * </ul>
 *
 * Удаление Группы свободно всегда: она уносит только свой список,
 * а не Учеников (ADR-0035). Проверки «на Группу ничего не ссылается»
 * нет намеренно — ссылаться на Группу нечему.
 */
@Service
public class GroupService {

    private final GroupRepository groups;
    private final StudentRepository students;
    private final CurrentUser currentUser;

    public GroupService(GroupRepository groups, StudentRepository students, CurrentUser currentUser) {
        this.groups = groups;
        this.students = students;
        this.currentUser = currentUser;
    }

    /** Все Группы вошедшего Учителя, по алфавиту. */
    @PreAuthorize("hasRole('TEACHER')")
    public List<Group> all() {
        return groups.findAll(owner());
    }

    /** Группа вошедшего Учителя; чужая или несуществующая — {@link GroupNotFoundException}. */
    @PreAuthorize("hasRole('TEACHER')")
    public Group group(GroupId id) {
        return existing(owner(), id);
    }

    /**
     * Заводит пустую Группу у вошедшего Учителя.
     *
     * @throws IllegalArgumentException имя пустое
     * @throws NameAlreadyTakenException такое имя у Учителя уже есть, в любом регистре
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public GroupId create(String name) {
        UserId owner = owner();
        String trimmed = requireName(name);
        refuseIfNameTaken(owner, trimmed, null);
        return groups.create(owner, trimmed);
    }

    /**
     * Меняет имя Группы. Сама запись при проверке занятости не считается:
     * переименование в то же имя с другим регистром законно.
     *
     * @throws GroupNotFoundException Группа чужая или её нет
     * @throws IllegalArgumentException имя пустое
     * @throws NameAlreadyTakenException такое имя носит другая Группа Учителя
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void rename(GroupId id, String newName) {
        UserId owner = owner();
        Group group = existing(owner, id);
        String trimmed = requireName(newName);
        refuseIfNameTaken(owner, trimmed, group.id());
        groups.rename(owner, group.id(), trimmed);
    }

    /** Удаляет Группу вместе с её списком; Ученики остаются (ADR-0035). */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void delete(GroupId id) {
        UserId owner = owner();
        Group group = existing(owner, id);
        groups.delete(owner, group.id());
    }

    /** Состав Группы — Ученики по алфавиту. */
    @PreAuthorize("hasRole('TEACHER')")
    public List<Student> members(GroupId id) {
        UserId owner = owner();
        Group group = existing(owner, id);
        List<Student> members = new ArrayList<>();
        for (StudentId studentId : groups.members(owner, group.id())) {
            students.findById(owner, studentId).ifPresent(members::add);
        }
        return members;
    }

    /**
     * Задаёт состав Группы целиком: прежний список заменяется указанным.
     * Пустой список опустошает Группу.
     *
     * Каждый Ученик читается с владельцем, и первый чужой или несуществующий
     * останавливает операцию до записи — состав не меняется ни на строку.
     *
     * @throws GroupNotFoundException Группа чужая или её нет
     * @throws StudentNotFoundException в списке чужой или несуществующий Ученик
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void setMembers(GroupId id, List<StudentId> studentIds) {
        UserId owner = owner();
        Group group = existing(owner, id);
        List<StudentId> own = new ArrayList<>();
        for (StudentId studentId : studentIds) {
            Student student = students.findById(owner, studentId)
                    .orElseThrow(() -> new StudentNotFoundException(studentId));
            own.add(student.id());
        }
        groups.setMembers(owner, group.id(), own);
    }

    /**
     * Группы вошедшего Учителя, в которых состоит его Ученик, по алфавиту.
     *
     * @throws StudentNotFoundException Ученик чужой или его нет
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<Group> groupsOf(StudentId studentId) {
        UserId owner = owner();
        Student student = students.findById(owner, studentId)
                .orElseThrow(() -> new StudentNotFoundException(studentId));
        return groups.groupsOf(owner, student.id());
    }

    private UserId owner() {
        return currentUser.id();
    }

    private Group existing(UserId owner, GroupId id) {
        return groups.findById(owner, id).orElseThrow(() -> new GroupNotFoundException(id));
    }

    /**
     * @param keep запись, которой разрешено носить это имя, — сама
     *             переименовываемая Группа; при заведении {@code null}
     */
    private void refuseIfNameTaken(UserId owner, String name, GroupId keep) {
        boolean taken = groups.findByName(owner, name)
                .filter(other -> !other.id().equals(keep))
                .isPresent();
        if (taken) {
            throw new NameAlreadyTakenException(name);
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Имя Группы не может быть пустым");
        }
        return trimmed;
    }
}
