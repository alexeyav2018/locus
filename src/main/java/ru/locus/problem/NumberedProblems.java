package ru.locus.problem;

import java.util.List;

/**
 * Ответ прямого выбора по номерам (ADR-0064): найденные Задачи и номера,
 * которых в библиотеке нет.
 *
 * Ненайденные — часть ответа, а не отказ: опечатка в одном номере
 * не прячет остальные, но и не теряется молча — экран называет её
 * строкой «Нет в библиотеке», Помощник получает списком.
 *
 * @param found   найденные Задачи с разметкой именами, по убыванию номера
 * @param missing номера, которых в библиотеке нет, по убыванию
 */
public record NumberedProblems(List<FoundProblem> found, List<Long> missing) {

    public NumberedProblems {
        found = List.copyOf(found);
        missing = List.copyOf(missing);
    }
}
