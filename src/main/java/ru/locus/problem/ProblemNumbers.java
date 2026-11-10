package ru.locus.problem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Номера Задач, по которым ищут прямым выбором (ADR-0064): ровно эти
 * Задачи, что бы ни стояло в условиях разметки.
 *
 * <p>Отдельный тип, а не разбор в контроллере: правило одно у страницы
 * поиска и у Помощника ({@code find_problems}), а это два клиента
 * сервиса — разбор в каждом разошёлся бы молча. И не поле
 * {@link ProblemFilter}: номера фильтр отменяют, а не дополняют, и запись,
 * у которой при одном поле остальные ничего не значат, читалась бы неверно.
 *
 * <p>Номер хранится числом, а не {@link ProblemId}: «0» законно разобран
 * и законно не найден — Задачи с таким номером не бывает, и учитель
 * увидит его в строке «Нет в библиотеке», а не отказ разбора.
 *
 * <p>Повтор снимается здесь же; порядок ввода значения не имеет —
 * результат идёт по убыванию номера (спека {@code library-search}).
 *
 * @param values разобранные номера без повторов, в порядке ввода
 */
public record ProblemNumbers(List<Long> values) {

    /** Разделители в любой смеси: запятые, точки с запятой, пробелы, переводы строк. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,;]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    public ProblemNumbers {
        values = values.stream().distinct().toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Не задано ни одного номера");
        }
    }

    /**
     * Номера из поля формы.
     *
     * <p>Кусок не из одних цифр — отказ с названием <b>всех</b> таких
     * кусков, а не первого: исправлять по одному, отправляя форму заново,
     * учитель не должен. Диапазон «3-7» — такой же посторонний кусок
     * (ADR-0064, «Диапазоны»). Число, не влезающее в {@code long}, —
     * тоже: номера такого не бывает, а молча обрезанное оно нашло бы
     * чужую Задачу.
     *
     * @throws IllegalArgumentException если есть неразобранные куски
     *                                  или номеров нет вовсе
     */
    public static ProblemNumbers parse(String text) {
        List<Long> values = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        for (String piece : SEPARATORS.split(text == null ? "" : text.strip())) {
            if (piece.isEmpty()) {
                continue;
            }
            Long value = DIGITS.matcher(piece).matches() ? number(piece) : null;
            if (value == null) {
                unparsed.add("«" + piece + "»");
            } else {
                values.add(value);
            }
        }
        if (!unparsed.isEmpty()) {
            throw new IllegalArgumentException("Не разобрано: " + String.join(", ", unparsed));
        }
        return new ProblemNumbers(values);
    }

    /** Номера от Помощника: тип схемы уже отсёк постороннее, правила повтора и пустоты — те же. */
    public static ProblemNumbers of(List<Long> values) {
        return new ProblemNumbers(values == null ? List.of() : values);
    }

    /** Номера, которые могут быть номерами Задач: положительные. */
    List<ProblemId> ids() {
        return values.stream().filter(value -> value > 0).map(ProblemId::new).toList();
    }

    private static Long number(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }
}
