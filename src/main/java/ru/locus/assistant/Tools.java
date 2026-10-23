package ru.locus.assistant;

import java.util.List;

/**
 * Инструменты одной предметной области — то, что область предлагает
 * Помощнику.
 *
 * <p>Интерфейс-вопрос по образцу {@code NodeContent}: область реализует
 * его рядом со своим контроллером ({@code ProblemTools},
 * {@code TaxonomyTools}…), а {@link ToolRegistry} собирает реализации
 * списком и не знает ни одной по имени. Зависимость идёт от области
 * к этому пакету, как от области к Spring MVC; обратной нет.
 */
public interface Tools {

    List<Tool> tools();
}
