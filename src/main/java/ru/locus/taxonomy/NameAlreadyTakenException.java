package ru.locus.taxonomy;

/**
 * Имя узла уникально среди потомков одного родителя.
 *
 * Уникальность именно среди братьев, а не по всему дереву: «признаки подобия»
 * — законное имя и под треугольниками, и под окружностями. А вот два
 * одинаковых имени в одном списке неразличимы для того, кто выбирает Тему
 * для задачи.
 */
public class NameAlreadyTakenException extends RuntimeException {

    private final String name;

    public NameAlreadyTakenException(String name) {
        super("Имя «" + name + "» уже занято среди узлов того же родителя");
        this.name = name;
    }

    public String name() {
        return name;
    }
}
