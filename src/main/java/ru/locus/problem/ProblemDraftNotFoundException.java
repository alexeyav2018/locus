package ru.locus.problem;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Черновика Задачи с таким идентификатором у вошедшего нет.
 *
 * Чужой черновик и несуществующий для сервиса неразличимы: репозиторий
 * отвечает пусто в обоих случаях, потому что владелец стоит в самом запросе
 * (ADR-0027). Различие выдало бы существование чужого черновика, поэтому
 * и ответ наружу один — 404 на странице и тот же отказ у инструмента
 * Помощника (problem-intake, спека, «Чужой черновик — 404»).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ProblemDraftNotFoundException extends RuntimeException {
    public ProblemDraftNotFoundException(ProblemDraftId id) {
        super("Черновика Задачи с идентификатором " + id.value() + " нет");
    }
}
