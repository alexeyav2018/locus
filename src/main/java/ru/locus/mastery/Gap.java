package ru.locus.mastery;

import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;

/**
 * Пробел на экране Владения — ячейка со статусом {@link
 * MasteryStatus#NOT_MASTERED}, с полным путём Темы и именем Метода для
 * показа и ссылкой в поиск по этой паре (`mastery-views`).
 */
public record Gap(TaxonomyNodeId topic, String topicPath, SolutionMethodId method, String methodName) {

    public Gap {
        if (topic == null) {
            throw new IllegalArgumentException("У пробела должна быть Тема");
        }
        if (topicPath == null || topicPath.isBlank()) {
            throw new IllegalArgumentException("У пробела должен быть путь Темы");
        }
        if (method == null) {
            throw new IllegalArgumentException("У пробела должен быть Метод");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("У пробела должно быть имя Метода");
        }
    }
}
