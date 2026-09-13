package ru.locus.assignment;

/**
 * Задание, как его показывают: с именем Ученика и вычисленным «не сдано».
 *
 * <p>Имя Ученика — текущее, а не запомненное: Ученик переименовывается
 * свободно (ADR-0035), и Задание должно показывать его так, как он
 * называется сейчас. Признак «не сдано» здесь <b>вычислен</b> сервисом
 * в момент чтения из срока, часов и ответа области Работ (инвариант 12
 * domain-model.md): в записи {@link Assignment} такого поля нет, и эта
 * запись — не хранилище признака, а его перевозка до экрана.
 */
public record ListedAssignment(Assignment assignment, String studentName, boolean notSubmitted) {

    public ListedAssignment {
        if (assignment == null) {
            throw new IllegalArgumentException("В списке не бывает пустого Задания");
        }
        if (studentName == null || studentName.isBlank()) {
            throw new IllegalArgumentException("У Задания в списке должен быть назван Ученик");
        }
    }
}
