package ru.locus.work;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.assignment.Assignment;
import ru.locus.assignment.AssignmentId;
import ru.locus.assignment.AssignmentService;
import ru.locus.file.FileKey;
import ru.locus.file.FileStorage;
import ru.locus.file.FileType;
import ru.locus.file.ImageCompression;
import ru.locus.problem.ProblemId;
import ru.locus.student.Student;
import ru.locus.student.StudentId;
import ru.locus.student.StudentService;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Ведение Работ: приём файлов по Задаче Задания, вердикт и примечание,
 * добавление и удаление файлов, удаление Работы, Работы по Заданию
 * и по Ученику (ADR-0015, ADR-0038).
 *
 * <p>Права проверяются здесь, а не в контроллере и не на адресах
 * (standards.md, «Слои и границы»); каждая операция — только Учителю,
 * включая чтение: у Пользователя без этой роли Работ нет.
 *
 * <p>Работы — ЛИЧНЫЙ контур, и по владельцу они <b>фильтруются всегда</b>
 * (ADR-0027): владелец берётся у {@link CurrentUser} в начале каждой
 * операции и передаётся в репозиторий, из запроса он не читается никогда.
 * Чужая Работа и несуществующая неразличимы — репозиторий отвечает пусто
 * в обоих случаях, наружу уходит один {@link StudentWorkNotFoundException}.
 * Задание и Ученик берутся у {@link AssignmentService} и
 * {@link StudentService} от имени того же вошедшего, так что чужое
 * Задание для приёма — несуществующее. Задачи, которые экран приёма
 * показывает, — общая библиотека, их отдаёт {@link AssignmentService#problemsOf}
 * без владельца.
 *
 * <p>Файлы — первый поток хранилища в личном контуре, и притом
 * персональные данные детей: наружу — только {@link FileStorage#temporaryLink},
 * ключ в разметку не попадает. Изображение пережимается, PDF кладётся
 * как есть, прочее отклоняется (ADR-0021; образец — {@code TheoryService}).
 * Порядок с несколькими файлами за одно действие: все проверки —
 * до первой укладки, укладка — до записи, при неудаче записи уложенное
 * убирается (standards.md, «Файлы»).
 *
 * <p>«Сегодня» для даты получения по умолчанию — у бина {@link Clock},
 * как у Заданий.
 */
@Service
public class StudentWorkService {

    private final StudentWorkRepository works;
    private final AssignmentService assignments;
    private final StudentService students;
    private final FileStorage storage;
    private final ImageCompression compression;
    private final CurrentUser currentUser;
    private final Clock clock;

    public StudentWorkService(StudentWorkRepository works,
                              AssignmentService assignments,
                              StudentService students,
                              FileStorage storage,
                              ImageCompression compression,
                              CurrentUser currentUser,
                              Clock clock) {
        this.works = works;
        this.assignments = assignments;
        this.students = students;
        this.storage = storage;
        this.compression = compression;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Принимает Работу по Задаче своего Задания: файлы, дата получения,
     * вердикт и примечание по желанию.
     *
     * <p>Проверки — до первого обращения к хранилищу: Задание своё
     * (чужое — {@link ru.locus.assignment.AssignmentNotFoundException}),
     * Задача в составе, файлов после отбрасывания пустых хотя бы один,
     * тип каждого — изображение или PDF. Отказ по типу одного файла
     * не оставляет в хранилище остальных. Срок Задания на приём не влияет:
     * Работа с опозданием принимается (ADR-0016).
     *
     * <p>Вторая Работа на паре не вставляется — уникальность в схеме
     * (ADR-0038); это ровно тот случай, когда запись падает после
     * укладки, и уложенные ключи убираются.
     *
     * @param receivedOn дата получения; {@code null} — сегодня
     * @param verdict    {@code null} — принять без вердикта, «не проверена»
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public StudentWorkId receive(AssignmentId assignmentId,
                                 ProblemId problemId,
                                 LocalDate receivedOn,
                                 Verdict verdict,
                                 String note,
                                 List<UploadedWorkFile> files) {
        Assignment assignment = assignments.assignment(assignmentId).assignment();
        if (!assignment.problems().contains(problemId)) {
            throw new IllegalArgumentException("Такой Задачи в Задании нет");
        }
        List<UploadedWorkFile> accepted = accepted(files);
        LocalDate received = receivedOn == null ? LocalDate.now(clock) : receivedOn;
        List<FileKey> keys = stored(accepted);
        try {
            return works.create(owner(), assignment.id(), problemId, received, verdict, note, keys);
        } catch (DuplicateKeyException duplicate) {
            discard(keys);
            throw new IllegalArgumentException("Работа по этой Задаче уже принята: файлы добавляются к ней");
        } catch (RuntimeException failure) {
            discard(keys);
            throw failure;
        }
    }

    /** Работа вошедшего Учителя; чужая или несуществующая — 404. */
    @PreAuthorize("hasRole('TEACHER')")
    public StudentWork work(StudentWorkId id) {
        return existing(owner(), id);
    }

    /**
     * Работы по своему Заданию — по Задачам состава: у Задачи либо одна
     * Работа, либо ни одной (ADR-0038). Чужое Задание — 404.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public Map<ProblemId, StudentWork> ofAssignment(AssignmentId assignmentId) {
        Assignment assignment = assignments.assignment(assignmentId).assignment();
        Map<ProblemId, StudentWork> byProblem = new LinkedHashMap<>();
        for (StudentWork work : works.findByAssignment(owner(), assignment.id())) {
            byProblem.put(work.problem(), work);
        }
        return byProblem;
    }

    /** Работы своего Ученика, новые первыми, — «вернуться к прошлым работам». Чужой Ученик — 404. */
    @PreAuthorize("hasRole('TEACHER')")
    public List<StudentWork> ofStudent(StudentId studentId) {
        Student student = students.student(studentId);
        return works.findByStudent(owner(), student.id());
    }

    /**
     * Файлы Работы с временными ссылками — считаются при каждом показе.
     * Ссылка живёт минуты; учитель, открывший страницу утром и нажавший
     * вечером, обновит её.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public List<LinkedFile> filesOf(StudentWork work) {
        List<LinkedFile> linked = new ArrayList<>();
        for (StudentWorkFile file : work.files()) {
            linked.add(new LinkedFile(file, storage.temporaryLink(file.key())));
        }
        return List.copyOf(linked);
    }

    /**
     * Добавляет файлы в конец своей Работы — повторная присылка по той же
     * Задаче (ADR-0038). Порядок тот же, что у приёма: проверки, укладка,
     * запись, уборка при неудаче.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void addFiles(StudentWorkId id, List<UploadedWorkFile> files) {
        UserId owner = owner();
        StudentWork work = existing(owner, id);
        List<UploadedWorkFile> accepted = accepted(files);
        List<FileKey> keys = stored(accepted);
        try {
            works.addFiles(owner, work.id(), keys);
        } catch (RuntimeException failure) {
            discard(keys);
            throw failure;
        }
    }

    /**
     * Удаляет один файл своей Работы — кроме последнего: Работа без файла
     * не существует, удаляется Работа целиком (ADR-0015, ADR-0038).
     * Строка уходит первой, затем ключ: забытый файл в хранилище никому
     * не мешает, а запись без файла выглядела бы настоящей.
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void deleteFile(StudentWorkId id, StudentWorkFileId fileId) {
        UserId owner = owner();
        StudentWork work = existing(owner, id);
        StudentWorkFile file = work.files().stream()
                .filter(candidate -> candidate.id().equals(fileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Такого файла у Работы нет"));
        if (work.files().size() == 1) {
            throw new IllegalArgumentException("Работа без файла не существует: удалите Работу целиком");
        }
        works.deleteFile(owner, work.id(), file.id());
        storage.delete(file.key());
    }

    /**
     * Ставит, меняет или снимает вердикт вместе с примечанием — в любой
     * момент после приёма (ADR-0038). Примечание без вердикта законно:
     * «посмотрю позже».
     *
     * @param verdict {@code null} — снять: Работа снова «не проверена»
     */
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public void setVerdict(StudentWorkId id, Verdict verdict, String note) {
        UserId owner = owner();
        StudentWork work = existing(owner, id);
        works.setVerdict(owner, work.id(), verdict, note == null ? "" : note.strip());
    }

    /**
     * Удаляет свою Работу целиком — в любой момент, вместе с файлами
     * (ADR-0038): по ключу на Работу ничего не ссылается. Ключи собраны
     * до удаления, строки уходят одним действием (файлы — каскадом),
     * затем каждый файл убирается из хранилища. Задание и Ученик остаются.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public void delete(StudentWorkId id) {
        UserId owner = owner();
        StudentWork work = existing(owner, id);
        works.delete(owner, work.id());
        for (StudentWorkFile file : work.files()) {
            storage.delete(file.key());
        }
    }

    /**
     * Сегодняшняя дата по часам сервера — для поля «дата получения»
     * формы приёма по умолчанию. Из сервиса, а не из контроллера:
     * «сегодня» в системе одно, у бина {@link Clock}, и тест сдвигает его
     * в одном месте.
     */
    @PreAuthorize("hasRole('TEACHER')")
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Пустые файлы отброшены — это поля формы без выбранного файла;
     * остаток непуст, и у каждого тип — изображение или PDF. Всё —
     * до первого обращения к хранилищу.
     */
    private static List<UploadedWorkFile> accepted(List<UploadedWorkFile> files) {
        List<UploadedWorkFile> accepted = files == null
                ? List.of()
                : files.stream().filter(file -> !file.isEmpty()).toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("Нужен хотя бы один файл");
        }
        for (UploadedWorkFile file : accepted) {
            if (!FileType.isImage(file.contentType()) && !FileType.PDF.equals(file.contentType())) {
                throw new IllegalArgumentException("Принимаются изображения и PDF");
            }
        }
        return accepted;
    }

    /**
     * Кладёт файлы в хранилище по порядку и возвращает ключи. Изображение
     * пережимается, PDF — как есть; тип — по слову браузера, содержимое
     * хранилище не разбирает, а пережатие на не-изображении упадёт само
     * (standards.md, «Файлы»). Сбой на n-м файле убирает n−1 уложенных.
     */
    private List<FileKey> stored(List<UploadedWorkFile> files) {
        List<FileKey> keys = new ArrayList<>();
        try {
            for (UploadedWorkFile file : files) {
                if (FileType.isImage(file.contentType())) {
                    ImageCompression.Compressed compressed = compression.compress(file.content());
                    keys.add(storage.put(compressed.content(), compressed.contentType()));
                } else {
                    keys.add(storage.put(file.content(), file.contentType()));
                }
            }
        } catch (RuntimeException failure) {
            discard(keys);
            throw failure;
        }
        return keys;
    }

    /**
     * Уборка уложенного при неудаче. Сбой самой уборки не должен подменять
     * собой исходную ошибку: учитель должен увидеть, почему Работа
     * не принялась, а не почему не удалось убрать за собой. Оставшийся
     * файл никому не мешает — он не адресуется ниоткуда.
     */
    private void discard(List<FileKey> keys) {
        for (FileKey key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // Исходная ошибка важнее; см. пояснение выше.
            }
        }
    }

    private UserId owner() {
        return currentUser.id();
    }

    private StudentWork existing(UserId owner, StudentWorkId id) {
        return works.findById(owner, id).orElseThrow(() -> new StudentWorkNotFoundException(id));
    }
}
