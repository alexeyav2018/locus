package ru.locus.problem;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Черновики Задачи: то, что распознал Помощник и вычитывает учитель,
 * до заведения Задачи (ADR-0059).
 *
 * <p>Ведёт их только Администратор (problem-intake, design.md, Р2): строки
 * ссылаются на Черновики сборки, которые загружает только он, и Задачу
 * из черновика заводит тоже он. Черновик принадлежит загрузившему:
 * владельца сервис берёт из {@link CurrentUser}, а не из запроса (ADR-0027),
 * и чужой черновик неотличим от несуществующего —
 * {@link ProblemDraftNotFoundException} у обоих.
 *
 * <p>Пишут черновик двое — страница вычитки и инструменты Помощника — и оба
 * одним методом {@link #write}; кто правил, передаёт вызывающий константой
 * (Р3). Запись, сохранение Задачей и удаление несут прочитанную версию,
 * ушедшая вперёд хранимая отклоняет их ({@link ProblemDraftChangedException}).
 *
 * <p>Черновик держит Черновики сборки, на которые ссылаются его строки:
 * пока он жив, их не убирает ни сохранение Задачи по соседнему черновику,
 * ни срок (условие держания — в {@link AssemblyDraftRepository}). Поэтому
 * обе уборки идут в одном порядке — сначала Черновики Задачи, затем
 * Черновики сборки: освобождённые уходят той же уборкой.
 *
 * <p>Уборка идёт попутно, без расписания (ADR-0017): {@link #sweep()} —
 * по сроку от последней правки, {@link #clearAll()} — при старте.
 */
@Service
public class ProblemDraftService {

    /** Чем утверждается черновик. */
    public enum Kind {
        /** Задача с текстом: тексты без пометок, Улики из строк сборки. */
        TEXT,
        /** Задача без текста: PDF слотов собраны из строк сборки как есть. */
        SNAPSHOT
    }

    private final ProblemDraftRepository drafts;
    private final AssemblyDraftService assemblyDrafts;
    private final ProblemService problems;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final ProblemDraftProperties properties;

    public ProblemDraftService(ProblemDraftRepository drafts,
                               AssemblyDraftService assemblyDrafts,
                               ProblemService problems,
                               CurrentUser currentUser,
                               Clock clock,
                               ProblemDraftProperties properties) {
        this.drafts = drafts;
        this.assemblyDrafts = assemblyDrafts;
        this.problems = problems;
        this.currentUser = currentUser;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Заводит черновик у вошедшего, попутно убрав брошенные. Содержимое
     * проверяется так же, как при записи.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public ProblemDraft create(ProblemDraftContent content, ProblemDraft.Editor editor) {
        sweep();
        check(content);
        UserId owner = currentUser.id();
        ProblemDraftId id = drafts.create(owner, content, editor, clock.instant());
        return own(owner, id);
    }

    /** Свои черновики в порядке заведения, попутно убрав брошенные. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public List<ProblemDraft> drafts() {
        sweep();
        return drafts.findByOwner(currentUser.id());
    }

    /**
     * Сколько своих черновиков живо — для ссылки на очередь вычитки.
     * Уборки здесь нет: ссылку показывает страница Помощника, а убирают
     * список и заведение (Р4); брошенный, но ещё не убранный черновик
     * не считается, чтобы число сошлось со списком.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public int count() {
        Instant alive = clock.instant().minus(properties.ttl());
        return (int) drafts.findByOwner(currentUser.id()).stream()
                .filter(draft -> !draft.changedAt().isBefore(alive))
                .count();
    }

    /** Свой черновик; чужой и несуществующий — один отказ. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ProblemDraft draft(ProblemDraftId id) {
        return own(currentUser.id(), id);
    }

    /**
     * Записывает изменение черновика, прочитанного с версией {@code version},
     * и возвращает записанный. Названное в изменении заменяется, прочее
     * остаётся (Р3).
     *
     * <p>Проверки при записи: Темы — листья, Методы и Характеристики есть
     * в словарях, строки сборки — по своим Черновикам сборки и в их пределах.
     * Тексты при записи не отрисовываются: неразбираемая формула мешает
     * сохранить Задачу, а не хранить черновик.
     *
     * @throws ProblemDraftNotFoundException черновика нет или он чужой
     * @throws ProblemDraftChangedException  хранимая версия другая
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public ProblemDraft write(ProblemDraftId id, long version, ProblemDraftChange change, ProblemDraft.Editor editor) {
        UserId owner = currentUser.id();
        ProblemDraft current = current(owner, id, version);
        ProblemDraftContent content = change.applyTo(current.content());
        check(content);
        OptionalLong written = drafts.write(owner, id, version, content, editor, clock.instant());
        if (written.isEmpty()) {
            throw refusal(owner, id);
        }
        return own(owner, id);
    }

    /**
     * Утверждает черновик, прочитанный с версией {@code version}: заводит
     * Задачу и удаляет черновик — одной транзакцией (Р4).
     *
     * <p>Порядок важен: строка черновика удаляется <b>до</b> заведения Задачи,
     * и к уборке использованных Черновиков сборки ссылок этого черновика
     * уже нет — последний черновик листа забирает исходник, а другие его
     * держат. Отказ заведения откатывает и удаление черновика.
     *
     * <p>Введённое на странице сюда не приходит: страница записывает его
     * отдельным вызовом {@link #write} прежде, чтобы отказ заведения его
     * не откатил.
     *
     * @return номер заведённой Задачи
     * @throws ProblemDraftNotFoundException черновика нет или он чужой
     * @throws ProblemDraftChangedException  хранимая версия другая
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public ProblemId save(ProblemDraftId id, long version, Kind kind) {
        UserId owner = currentUser.id();
        ProblemDraftContent content = current(owner, id, version).content();
        if (!drafts.delete(owner, id, version)) {
            throw refusal(owner, id);
        }
        return switch (kind) {
            case TEXT -> problems.create(content.part(), content.topics(), content.methods(),
                    content.characteristics(),
                    DoubtMarks.strip(content.conditionText()), DoubtMarks.strip(content.solutionText()),
                    content.condition(), content.solution());
            case SNAPSHOT -> problems.create(content.part(), content.topics(), content.methods(),
                    content.characteristics(), content.condition(), content.solution());
        };
    }

    /**
     * Удаляет черновик, прочитанный с версией {@code version}; Задачи
     * удаление не заводит. Черновики сборки остаются до своей уборки.
     *
     * @throws ProblemDraftNotFoundException черновика нет или он чужой
     * @throws ProblemDraftChangedException  хранимая версия другая
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void delete(ProblemDraftId id, long version) {
        UserId owner = currentUser.id();
        if (!drafts.delete(owner, id, version)) {
            throw refusal(owner, id);
        }
    }

    /**
     * Улики слотов для страницы вычитки — PDF, собранные из строк сборки,
     * картинками страниц (Р6). Слот без строк — без картинок. Отказ сборки
     * показывается на месте Улики, а не роняет страницу.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public TextPreview evidence(ProblemDraft draft) {
        try {
            return TextPreview.of(new RenderedText(assembled(draft.content().condition()),
                    assembled(draft.content().solution())));
        } catch (IllegalArgumentException refusal) {
            return TextPreview.refused(refusal.getMessage());
        }
    }

    /**
     * Отрисовка текстов черновика без пометок для страницы вычитки (Р5, Р6);
     * неразбираемая формула — отказ на месте отрисовки.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public TextPreview rendering(ProblemDraft draft) {
        try {
            return TextPreview.of(problems.preview(DoubtMarks.strip(draft.content().conditionText()),
                    DoubtMarks.strip(draft.content().solutionText())));
        } catch (IllegalArgumentException refusal) {
            return TextPreview.refused(refusal.getMessage());
        }
    }

    /**
     * Уборка по сроку: черновики <b>всех</b> Пользователей, не правленные
     * дольше срока, а за ними — Черновики сборки старше своего срока,
     * которые больше никто не держит. Черновик, к которому вернулись, жив.
     */
    @Transactional
    public void sweep() {
        drafts.deleteChangedBefore(clock.instant().minus(properties.ttl()));
        assemblyDrafts.sweep();
    }

    /**
     * Уборка при старте: все черновики Задачи и затем все Черновики сборки —
     * в таком порядке, иначе строки сборки не дали бы удалить исходники.
     */
    @Transactional
    public void clearAll() {
        drafts.deleteAll();
        assemblyDrafts.clearAll();
    }

    private void check(ProblemDraftContent content) {
        problems.requireDraftMarkup(content.topics(), content.methods(), content.characteristics());
        assemblyDrafts.check(content.condition());
        assemblyDrafts.check(content.solution());
    }

    private byte[] assembled(PdfAssemblyOrder order) {
        return order.isEmpty() ? null : assemblyDrafts.assemble(order);
    }

    /** Свой черновик с прочитанной версией; иначе отказ. */
    private ProblemDraft current(UserId owner, ProblemDraftId id, long version) {
        ProblemDraft current = own(owner, id);
        if (current.version() != version) {
            throw new ProblemDraftChangedException();
        }
        return current;
    }

    private ProblemDraft own(UserId owner, ProblemDraftId id) {
        return drafts.find(owner, id).orElseThrow(() -> new ProblemDraftNotFoundException(id));
    }

    /**
     * Запись или удаление не тронули ни строки: второе чтение с владельцем
     * различает «нет такого или чужой» и «изменён» (Р3).
     */
    private RuntimeException refusal(UserId owner, ProblemDraftId id) {
        return drafts.find(owner, id).isPresent()
                ? new ProblemDraftChangedException()
                : new ProblemDraftNotFoundException(id);
    }
}
