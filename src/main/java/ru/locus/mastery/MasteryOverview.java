package ru.locus.mastery;

import ru.locus.student.Student;

import java.util.List;

/**
 * Экран Владения одного Ученика целиком: дерево от корней, таблица
 * по Методу и перечень пробелов (`mastery-views`).
 */
public record MasteryOverview(Student student,
                              List<MasteryBranch> tree,
                              List<MasteryOfMethodRow> methods,
                              List<Gap> gaps) {

    public MasteryOverview {
        if (student == null) {
            throw new IllegalArgumentException("У экрана владения должен быть Ученик");
        }
        tree = List.copyOf(tree);
        methods = List.copyOf(methods);
        gaps = List.copyOf(gaps);
    }
}
