package ru.locus.student;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Ученика с таким идентификатором у вошедшего Учителя нет.
 *
 * Чужой Ученик и несуществующий для сервиса неразличимы: репозиторий
 * отвечает пусто в обоих случаях, потому что владелец стоит в самом запросе
 * (ADR-0027). Различие выдало бы существование чужой карточки, поэтому
 * и ответ наружу один — 404 (спека, «Прямой адрес чужого Ученика»).
 * В библиотечных областях несуществующая запись отдаёт стандартную ошибку;
 * здесь ответ важен по существу, и он назван.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(StudentId id) {
        super("Ученика с идентификатором " + id.value() + " не существует");
    }
}
