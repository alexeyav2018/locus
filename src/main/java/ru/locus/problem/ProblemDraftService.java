package ru.locus.problem;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Черновики Задачи: то, что распознал Помощник и вычитывает учитель,
 * до заведения Задачи (ADR-0059).
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

    private final ProblemDraftRepository drafts;
    private final AssemblyDraftService assemblyDrafts;
    private final Clock clock;
    private final ProblemDraftProperties properties;

    public ProblemDraftService(ProblemDraftRepository drafts,
                               AssemblyDraftService assemblyDrafts,
                               Clock clock,
                               ProblemDraftProperties properties) {
        this.drafts = drafts;
        this.assemblyDrafts = assemblyDrafts;
        this.clock = clock;
        this.properties = properties;
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
}
