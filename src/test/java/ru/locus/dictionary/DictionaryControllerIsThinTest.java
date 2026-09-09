package ru.locus.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Требование «Контроллеры тонкие» (standards.md, «Слои и границы»).
 *
 * Разделение слоёв держится не на расположении папок, а на том, что
 * контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверки прав, и правила словаря.
 */
class DictionaryControllerIsThinTest {

    private static final List<Class<?>> REPOSITORIES =
            List.of(SolutionMethodRepository.class, CharacteristicRepository.class);

    @Test
    void controllerDoesNotHoldARepository() {
        for (Field field : DictionaryController.class.getDeclaredFields()) {
            assertThat(REPOSITORIES)
                    .as("поле %s не должно быть репозиторием", field.getName())
                    .doesNotContain(field.getType());
        }
    }

    @Test
    void controllerIsNotEvenGivenARepository() {
        for (Constructor<?> constructor : DictionaryController.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes())
                    .as("репозитории контроллеру не передаются")
                    .doesNotContain(SolutionMethodRepository.class, CharacteristicRepository.class);
        }
    }
}
