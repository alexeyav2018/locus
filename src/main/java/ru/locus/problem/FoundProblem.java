package ru.locus.problem;

import java.util.List;

/**
 * Найденная Задача — сама {@link Problem} и её разметка, уже названная
 * по-человечески.
 *
 * <p>Имена собираются в сервисе, а не в шаблоне, потому что в шаблоне
 * вычислений не бывает (standards.md, «Слои и границы»). Соблазн есть:
 * {@link ProblemController#problem} переводит идентификаторы в имена
 * фильтрацией полного списка по каждой метке, и то же самое напрашивается
 * здесь. На одной Задаче это незаметно, на списке даёт перебор «все
 * найденные Задачи × весь словарь» — поэтому сервис читает словари и пути
 * по одному разу и раскладывает найденное по Задачам.
 *
 * <p>Темы названы <b>полными путями</b> от корня, а не короткими именами:
 * одинаковые имена под разными родителями законны («признаки подобия»
 * и под треугольниками, и под окружностями), и в списке результатов они
 * были бы неразличимы. Та же причина, по которой заведён
 * {@link ru.locus.taxonomy.TaxonomyPath}.
 *
 * <p>Идентификаторы при этом не выбрасываются: они остались внутри
 * {@link #problem()}, и по ним строится ссылка на саму Задачу.
 *
 * @param problem              найденная Задача целиком
 * @param topicPaths           Темы разметки полными путями, в порядке
 *                             разметки
 * @param methodNames          имена Методов разметки
 * @param characteristicNames  имена Характеристик разметки; список пуст,
 *                             если Характеристик нет
 */
public record FoundProblem(Problem problem,
                           List<String> topicPaths,
                           List<String> methodNames,
                           List<String> characteristicNames) {

    public FoundProblem {
        topicPaths = List.copyOf(topicPaths);
        methodNames = List.copyOf(methodNames);
        characteristicNames = List.copyOf(characteristicNames);
    }

    /** Номер Задачи — то, чем она названа человеку. */
    public long number() {
        return problem.number();
    }
}
