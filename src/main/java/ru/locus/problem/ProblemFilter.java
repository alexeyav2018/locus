package ru.locus.problem;

import java.util.List;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Условия поиска Задач в общей библиотеке (ADR-0031).
 *
 * Отдельный тип, а не россыпь параметров у метода: у поиска будет второй
 * вход — подбор по пробелам ученика ({@code mastery-views}), — и он должен
 * звать ту же операцию, а не свою похожую. Отдельный тип делает появление
 * нового условия правкой в одном месте; шесть аргументов заставляли бы
 * править каждый вызов, и забытый вызов компилировался бы, если типы
 * совпали. С двумя соседними списками и двумя соседними {@link MatchMode}
 * это не гипотеза: перепутать их местами ничего не мешает.
 *
 * <p>Каждое условие необязательно, заданные соединяются по «и». Условие
 * по узлу и по Части берёт не более одного значения: множество Тем
 * поддерева вычисляет система, а не набирает учитель, а у Задачи ровно одна
 * Часть.
 *
 * <p><b>Пустой список — это отсутствие условия, а не условие из пустого
 * набора.</b> Разница тихая и потому опасная: понятое буквально, «все
 * сразу» из нуля значений истинно для всякой Задачи, а «любое из» из нуля —
 * ложно для всякой. Ни одно из двух прочтений не имеется в виду, когда
 * учитель просто не тронул список, поэтому пустое условие снимается здесь
 * же, при сборке Фильтра, и до запроса не доходит.
 *
 * <p>Библиотека общая: владельца у Задачи нет, и параметра «чей» здесь
 * нет и быть не должно (ADR-0027).
 *
 * @param node               узел рубрикатора; поиск идёт по всему его
 *                           поддереву. {@code null} — условия нет
 * @param methods            выбранные Методы; пустой список — условия нет
 * @param methodMode         как соединять выбранные Методы
 * @param characteristics    выбранные Характеристики; пустой список —
 *                           условия нет
 * @param characteristicMode как соединять выбранные Характеристики
 * @param part               Часть ЕГЭ; {@code null} — условия нет
 */
public record ProblemFilter(TaxonomyNodeId node,
                            List<SolutionMethodId> methods,
                            MatchMode methodMode,
                            List<CharacteristicId> characteristics,
                            MatchMode characteristicMode,
                            ExamPart part) {

    public ProblemFilter {
        methods = chosen(methods);
        characteristics = chosen(characteristics);
        methodMode = orDefault(methodMode);
        characteristicMode = orDefault(characteristicMode);
    }

    /** Фильтр без единого условия: отбирает всю библиотеку. */
    public static ProblemFilter empty() {
        return new ProblemFilter(null, List.of(), null, List.of(), null, null);
    }

    /** Не задано ни одного условия — отбирать нечего, вернётся вся библиотека. */
    public boolean isEmpty() {
        return node == null && methods.isEmpty() && characteristics.isEmpty() && part == null;
    }

    /**
     * Выбранные значения: {@code null} и пустой список — одно и то же,
     * дважды указанное значение — одно значение.
     *
     * Дважды указанное снимается не ради опрятности: в режиме «все сразу»
     * повторённое значение дало бы второй такой же подзапрос, а в режиме
     * «любое из» — второй такой же элемент перечисления. Оба безвредны
     * для ответа и оба делают запрос нечитаемым при разборе.
     */
    private static <T> List<T> chosen(List<T> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    /**
     * Умолчание — «любое из»: расширяющее прочтение может показать лишнее,
     * это видно и поправимо, тогда как сужающее показало бы меньше, чем
     * есть, и этого не видно вовсе (ADR-0031).
     */
    private static MatchMode orDefault(MatchMode mode) {
        return mode == null ? MatchMode.ANY : mode;
    }
}
