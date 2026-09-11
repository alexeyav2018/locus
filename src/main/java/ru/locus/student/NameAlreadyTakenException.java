package ru.locus.student;

/**
 * Имя Группы уникально среди Групп одного Учителя — и без учёта регистра.
 *
 * Границы уникальности здесь свои, поэтому и исключение своё, а не
 * словарное ({@link ru.locus.dictionary.NameAlreadyTakenException}):
 * у словаря имя занято во всей общей библиотеке, у Группы — только
 * у одного владельца, и «9Б» двух Учителей друг другу не мешают
 * (индекс {@code uq_group_user_name} включает {@code user_id}).
 * Регистр не учитывается, потому что Группа выбирается по имени
 * при выдаче, и «9б» рядом с «9Б» неразличимы.
 *
 * На Учеников правило не распространяется: однофамильцы обычны,
 * и запрет заставил бы писать «Иванов-2» (design.md, «Имена»).
 */
public class NameAlreadyTakenException extends RuntimeException {

    private final String name;

    public NameAlreadyTakenException(String name) {
        super("Группа с именем «" + name + "» у вас уже есть");
        this.name = name;
    }

    public String name() {
        return name;
    }
}
