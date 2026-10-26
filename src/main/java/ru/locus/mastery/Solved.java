package ru.locus.mastery;

/**
 * Справка по паре «Тема × Метод» из одного основания: сколько раз Ученик
 * справился и сколько всего суждений об этом есть.
 *
 * Оснований два, и каждое считается своим запросом в тот же вид
 * ({@code Map<Cell, Solved>}): Работы — {@code StudentWorkRepository.countCheckedByPairs}
 * (оценено — с вердиктом, справился — вердикт «верно»; непроверенная
 * Работа не считается, ADR-0038) и Итоги Разминок —
 * {@code WarmupRepository.countOutcomesByPairs} (оценено — с итогом,
 * справился — «справился»; пустая клетка не хранится и не считается).
 * Это справка Учителю при простановке отметки, а не автоматика: суждение
 * выносит человек (ADR-0011, ADR-0014), и основания показываются порознь,
 * а не складываются (ADR-0061).
 *
 * @param succeeded сколько раз справился, 0..judged
 * @param judged    сколько суждений по паре всего, 0..n
 */
public record Solved(int succeeded, int judged) {

    /** Суждений по паре нет. */
    public static final Solved NONE = new Solved(0, 0);

    public Solved {
        if (succeeded < 0 || judged < succeeded) {
            throw new IllegalArgumentException("Успешных суждений не больше всех и не меньше нуля");
        }
    }
}
