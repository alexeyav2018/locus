package ru.locus.lesson;

import java.util.Objects;
import ru.locus.student.GroupId;
import ru.locus.student.StudentId;
import ru.locus.user.UserId;

/**
 * Занятие — хранимое правило расписания Учителя: с кем и когда
 * (ADR-0047). Встреч оно не хранит — их вычисляет {@code Meetings}
 * при показе недели.
 *
 * <p>Адресат ровно один: свой Ученик <em>или</em> своя Группа. Второе поле
 * всегда {@code null}; то же правило держит ограничение
 * {@code ck_lesson_one_addressee}, а принадлежность адресата тому же
 * владельцу — составные ключи таблицы.
 *
 * <p>Личный контур: у записи есть владелец, и ни один запрос к ней
 * не идёт без него (ADR-0027).
 */
public record Lesson(LessonId id, UserId owner, StudentId student, GroupId group, LessonTiming timing) {

    public Lesson {
        Objects.requireNonNull(id);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(timing);
        if ((student == null) == (group == null)) {
            throw new IllegalArgumentException("У Занятия ровно один адресат — Ученик или Группа");
        }
    }
}
