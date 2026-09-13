package ru.locus.assignment;

import ru.locus.student.GroupId;
import ru.locus.student.StudentId;

/**
 * Адресат выдачи — ровно один: Ученик либо Группа.
 *
 * <p>Форма выдачи показывает два списка, и вошедший может не выбрать
 * ни в одном или выбрать в обоих. Правило «адресат один» живёт здесь,
 * в типе области, а не в контроллере: контроллер лишь зовёт фабрику
 * и раскладывает результат по двум операциям сервиса —
 * {@code issueToStudent} и {@code issueToGroup}. Один метод сервиса с двумя
 * необязательными параметрами дал бы ветвление внутри и неясный тип ответа
 * (Задание или Раздача).
 *
 * <p>Тип запечатан: третьего адресата нет, и {@code switch} по нему
 * обязан разобрать оба случая — забытая ветка станет ошибкой компиляции,
 * а не молчаливым пропуском.
 */
public sealed interface Addressee {

    /** Задание выдаётся одному Ученику лично. */
    record ToStudent(StudentId student) implements Addressee {
        public ToStudent {
            if (student == null) {
                throw new IllegalArgumentException("Не указан Ученик");
            }
        }
    }

    /** Задание выдаётся каждому Ученику Группы, с общей Раздачей. */
    record ToGroup(GroupId group) implements Addressee {
        public ToGroup {
            if (group == null) {
                throw new IllegalArgumentException("Не указана Группа");
            }
        }
    }

    /**
     * Собирает адресата из того, что пришло с формы: два необязательных
     * числа, из которых заполнено должно быть ровно одно.
     *
     * @throws IllegalArgumentException если не выбран ни один адресат
     *                                  или выбраны оба
     */
    static Addressee of(Long student, Long group) {
        if ((student == null) == (group == null)) {
            throw new IllegalArgumentException("адресат один: Ученик либо Группа");
        }
        return student != null ? new ToStudent(new StudentId(student)) : new ToGroup(new GroupId(group));
    }
}
