package ru.locus.assignment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Раздачи с таким идентификатором у вошедшего Учителя нет — чужая
 * и несуществующая неразличимы, ответ один (ADR-0027); подробнее
 * в {@link AssignmentNotFoundException}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AssignmentBatchNotFoundException extends RuntimeException {
    public AssignmentBatchNotFoundException(AssignmentBatchId id) {
        super("Раздачи с идентификатором " + id.value() + " не существует");
    }
}
