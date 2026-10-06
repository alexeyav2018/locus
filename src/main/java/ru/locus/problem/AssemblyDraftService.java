package ru.locus.problem;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.locus.file.FileType;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Черновики сборки PDF Задачи: загрузка исходника, сборка из своих
 * черновиков, удаление после сохранения Задачи и уборка брошенного
 * (ADR-0044).
 *
 * <p>Черновик принадлежит загрузившему: владельца сервис берёт
 * из {@link CurrentUser}, а не из запроса (ADR-0027), и чужой черновик
 * для вызывающего неотличим от несуществующего — отказ у них один.
 *
 * <p>Содержимое — файл в рабочей папке, а не в хранилище файлов
 * ({@link AssemblyDraftProperties#directory()}): хранилищу пришлось бы
 * добавить чтение по ключу (ADR-0021). Строка в базе и файл на диске
 * общей транзакции не имеют, поэтому файлы сносятся только после фиксации
 * удаления строк: откат оставляет черновик целым, чтобы форма могла
 * показать тот же порядок сборки.
 *
 * <p>Уборка идёт попутно, без расписания (ADR-0017): {@link #sweep()} при
 * открытии инструмента убирает черновики всех старше срока,
 * {@link #clearAll()} при старте — все до одного.
 */
@Service
public class AssemblyDraftService {

    private static final int MAX_NAME_LENGTH = 500;

    private final AssemblyDraftRepository drafts;
    private final PdfAssembly assembly;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final AssemblyDraftProperties properties;

    public AssemblyDraftService(AssemblyDraftRepository drafts,
                                PdfAssembly assembly,
                                CurrentUser currentUser,
                                Clock clock,
                                AssemblyDraftProperties properties) {
        this.drafts = drafts;
        this.assembly = assembly;
        this.currentUser = currentUser;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Принимает исходник для сборки и заводит его черновик у вошедшего.
     *
     * <p>Поток пишется прямо в рабочую папку, сборник целиком в памяти
     * не держится. Вид определяется по первым байтам, а не по типу, который
     * назвал браузер: проверяется то, что пришло. PDF открывается сразу —
     * чтобы счесть страницы и отклонить неразбираемый и закрытый паролем
     * при загрузке, а не при сборке. Отклонённый исходник с диска убирается.
     *
     * <p>Загрузка — одно из открытий инструмента, поэтому сначала уборка.
     *
     * @throws IllegalArgumentException если исходник не JPEG, не PNG и не
     *                                  разбираемый PDF либо закрыт паролем
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public AssemblyDraft upload(String originalName, InputStream content) {
        sweep();
        UserId owner = currentUser.id();
        String name = displayName(originalName);
        String fileName = UUID.randomUUID().toString();
        Path file = fileOf(fileName);
        try {
            Files.createDirectories(properties.directory());
            Files.copy(content, file);
        } catch (IOException e) {
            removeQuietly(file);
            throw new UncheckedIOException("Не удалось принять исходник «" + name + "»", e);
        }
        try {
            byte[] head = head(file);
            AssemblyDraft.Kind kind;
            String contentType;
            int pageCount;
            if (PdfAssembly.isJpeg(head)) {
                kind = AssemblyDraft.Kind.IMAGE;
                contentType = FileType.JPEG;
                pageCount = 1;
            } else if (PdfAssembly.isPng(head)) {
                kind = AssemblyDraft.Kind.IMAGE;
                contentType = FileType.PNG;
                pageCount = 1;
            } else if (isPdf(head)) {
                kind = AssemblyDraft.Kind.PDF;
                contentType = FileType.PDF;
                pageCount = assembly.pageCount(file, name);
            } else {
                throw new IllegalArgumentException("Файл «" + name
                        + "» не подходит для сборки: принимаются картинки JPEG и PNG и файлы PDF");
            }
            AssemblyDraftId id = drafts.create(owner, name, kind, contentType, pageCount, fileName, clock.instant());
            return drafts.findByIds(owner, List.of(id)).getFirst();
        } catch (RuntimeException refusal) {
            removeQuietly(file);
            throw refusal;
        }
    }

    /**
     * Собирает PDF по порядку сборки из черновиков вошедшего.
     *
     * <p>Чужой и несуществующий черновик отклоняются одним отказом: по ответу
     * нельзя узнать, что черновик с таким номером у кого-то есть. Черновики
     * при сборке не удаляются — это делает {@link #discard} после того, как
     * Задача сохранена.
     *
     * @throws IllegalArgumentException если порядок пуст, черновика нет
     *                                  или диапазон выходит за его страницы
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public byte[] assemble(PdfAssemblyOrder order) {
        if (order.lines().isEmpty()) {
            throw new IllegalArgumentException("Не указано ни одного источника: собирать нечего");
        }
        Map<AssemblyDraftId, AssemblyDraft> own = drafts.findByIds(currentUser.id(), order.drafts()).stream()
                .collect(Collectors.toMap(AssemblyDraft::id, Function.identity()));
        List<PdfAssemblyPart> parts = new ArrayList<>();
        for (PdfAssemblyOrder.Line line : order.lines()) {
            AssemblyDraft draft = own.get(line.draft());
            if (draft == null) {
                throw new IllegalArgumentException(
                        "Исходника для сборки больше нет — вероятно, истёк его срок: загрузите его заново");
            }
            Path file = fileOf(draft.fileName());
            parts.add(switch (draft.kind()) {
                case IMAGE -> new PdfAssemblyPart.Image(file, draft.originalName());
                case PDF -> new PdfAssemblyPart.Pages(file, draft.originalName(), line.from(), line.to());
            });
        }
        return assembly.assemble(parts);
    }

    /**
     * Строки сборки для перерисовки формы после отказа: тот же порядок
     * с именами и числом страниц, чтобы сборник не загружать заново.
     *
     * <p>Черновик, которого у вошедшего нет — чужой или убранный по сроку, —
     * просто не попадает в строки: форма покажет, что осталось, а отказ
     * о пропавшем исходнике даст сборка при следующей отправке.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public List<AssemblyRow> rows(PdfAssemblyOrder order) {
        if (order.isEmpty()) {
            return List.of();
        }
        Map<AssemblyDraftId, AssemblyDraft> own = drafts.findByIds(currentUser.id(), order.drafts()).stream()
                .collect(Collectors.toMap(AssemblyDraft::id, Function.identity()));
        return order.lines().stream()
                .filter(line -> own.containsKey(line.draft()))
                .map(line -> new AssemblyRow(own.get(line.draft()), line.from(), line.to()))
                .toList();
    }

    /**
     * Удаляет черновики вошедшего — после того, как собранный из них PDF
     * лёг в Задачу. Строки удаляются в транзакции вызывающего, файлы —
     * после её фиксации; откат оставляет и то и другое. Чужие черновики
     * не трогаются.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void discard(Collection<AssemblyDraftId> ids) {
        removeAfterCommit(drafts.deleteByIds(currentUser.id(), ids));
    }

    /**
     * Уборка при открытии инструмента: черновики <b>всех</b> Пользователей
     * старше срока. Черновик моложе срока остаётся — его может ждать
     * незавершённая форма во второй вкладке (design.md, «Уборка»).
     */
    @Transactional
    public void sweep() {
        removeAfterCommit(drafts.deleteCreatedBefore(clock.instant().minus(properties.ttl())));
    }

    /**
     * Уборка при старте: все черновики и всё содержимое рабочей папки,
     * включая файлы, которым строки не досталось (оборванная загрузка).
     */
    @Transactional
    public void clearAll() {
        drafts.deleteAll();
        Path directory = properties.directory();
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, Files::isRegularFile)) {
                for (Path file : files) {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось очистить папку черновиков " + directory, e);
        }
    }

    private void removeAfterCommit(List<String> fileNames) {
        if (fileNames.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileNames.forEach(name -> removeQuietly(fileOf(name)));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileNames.forEach(name -> removeQuietly(fileOf(name)));
            }
        });
    }

    private Path fileOf(String fileName) {
        return properties.directory().resolve(fileName);
    }

    /**
     * Снос файла, который уже ничем не адресуется. Сбой не должен подменять
     * собой исходный отказ; оставшийся файл уберёт очистка при старте.
     */
    private static void removeQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // См. пояснение выше.
        }
    }

    private static byte[] head(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(16);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isPdf(byte[] head) {
        return head.length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F'
                && head[4] == '-';
    }

    private static String displayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "без имени";
        }
        String name = originalName.strip();
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }
}
