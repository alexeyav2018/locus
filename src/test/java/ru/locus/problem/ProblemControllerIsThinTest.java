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
 *
 * Смотрятся оба контроллера области: у поиска соблазн сходить в репозиторий
 * прямо больше прочих — отбор он и так собирает из параметров запроса,
 * и мимо сервиса ушёл бы вместе с ним обход поддерева.
 */
class ProblemControllerIsThinTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            ProblemController.class,
            ProblemSearchController.class);

    private static final List<Class<?>> FORBIDDEN = List.of(
            ProblemRepository.class,
            TaxonomyRepository.class,
            SolutionMethodRepository.class,
            CharacteristicRepository.class,
            FileStorage.class);

    @Test
    void controllerHoldsNeitherRepositoryNorStorage() {
        for (Class<?> controller : CONTROLLERS) {
            for (Field field : controller.getDeclaredFields()) {
                assertThat(FORBIDDEN)
                        .as("поле %s.%s не должно быть репозиторием или хранилищем",
                                controller.getSimpleName(), field.getName())
                        .doesNotContain(field.getType());
            }
        }
    }

    @Test
    void controllerIsNotEvenGivenOne() {
        for (Class<?> controller : CONTROLLERS) {
            for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("репозитории и хранилище контроллеру %s не передаются", controller.getSimpleName())
                        .doesNotContain(FORBIDDEN.toArray(Class<?>[]::new));
            }
        }
    }
}
