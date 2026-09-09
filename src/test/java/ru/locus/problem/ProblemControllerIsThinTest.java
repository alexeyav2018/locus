package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.dictionary.CharacteristicRepository;
import ru.locus.dictionary.SolutionMethodRepository;
import ru.locus.file.FileStorage;
import ru.locus.taxonomy.TaxonomyRepository;

/**
 * Требование «Контроллеры тонкие» (standards.md, «Слои и границы»).
 *
 * Разделение слоёв держится не на расположении папок, а на том, что
 * контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверки прав, и правила Задачи,
 * и порядок укладки файлов.
 *
 * Хранилище проверяется отдельным пунктом: файл, положенный контроллером
 * напрямую, обошёл бы и проверку состава Задачи, и уборку при неудаче —
 * и остался бы в хранилище сиротой (standards.md, «Файлы»).
 */
class ProblemControllerIsThinTest {

    private static final List<Class<?>> FORBIDDEN = List.of(
            ProblemRepository.class,
            TaxonomyRepository.class,
            SolutionMethodRepository.class,
            CharacteristicRepository.class,
            FileStorage.class);

    @Test
    void controllerHoldsNeitherRepositoryNorStorage() {
        for (Field field : ProblemController.class.getDeclaredFields()) {
            assertThat(FORBIDDEN)
                    .as("поле %s не должно быть репозиторием или хранилищем", field.getName())
                    .doesNotContain(field.getType());
        }
    }

    @Test
    void controllerIsNotEvenGivenOne() {
        for (Constructor<?> constructor : ProblemController.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes())
                    .as("репозитории и хранилище контроллеру не передаются")
                    .doesNotContain(FORBIDDEN.toArray(Class<?>[]::new));
        }
    }
}
