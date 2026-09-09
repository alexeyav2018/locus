package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Требование «Контроллеры тонкие» (standards.md, «Слои и границы»).
 *
 * Разделение слоёв держится не на расположении папок, а на том, что
 * контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверки прав, и правила дерева.
 */
class TaxonomyControllerIsThinTest {

    @Test
    void controllerDoesNotHoldTheRepository() {
        for (Field field : TaxonomyController.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("поле %s не должно быть репозиторием", field.getName())
                    .isNotEqualTo(TaxonomyRepository.class);
        }
    }

    @Test
    void controllerIsNotEvenGivenTheRepository() {
        for (Constructor<?> constructor : TaxonomyController.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes())
                    .as("репозиторий контроллеру не передаётся")
                    .doesNotContain(TaxonomyRepository.class);
        }
    }
}
