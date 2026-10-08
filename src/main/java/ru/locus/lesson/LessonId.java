package ru.locus.lesson;

/**
 * Идентификатор Занятия — отдельный тип, а не голое число.
 *
 * По образцу {@link ru.locus.student.StudentId}: в вызовах репозитория рядом
 * с ним стоят идентификаторы владельца, Ученика и Группы; перепутанные местами
 * числа тихо правили бы не ту запись, разные типы делают это ошибкой компиляции.
 */
public record LessonId(long value) {
    public LessonId {
        if (value <= 0) {
            throw new IllegalArgumentException("Идентификатор Занятия должен быть положительным: " + value);
        }
    }
}
