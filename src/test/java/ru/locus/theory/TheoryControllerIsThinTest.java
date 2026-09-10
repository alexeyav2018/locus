package ru.locus.theory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.file.FileStorage;
import ru.locus.taxonomy.TaxonomyRepository;

/**
 * Требование «Контроллеры тонкие» (standards.md, «Слои и границы»).
 *
 * Разделение слоёв держится не на расположении папок, а на том, что
 * контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверка роли Администратора, и правило
 * «содержимое ровно одно», и порядок укладки файлов.
 *
 * Хранилище проверяется отдельным пунктом, и для теории он весомее, чем для
 * Задачи: контроллер, положивший файл напрямую, обошёл бы и уборку при неудаче
 * сохранения, и удаление прежнего файла при замене — а оставшийся файл
 * не адресуется ниоткуда и отличить его от нужного потом невозможно
 * (standards.md, «Файлы»).
 */
class TheoryControllerIsThinTest {

    private static final List<Class<?>> FORBIDDEN = List.of(
            TheoryMaterialRepository.class,
            TaxonomyRepository.class,
            FileStorage.class);

    @Test
    void controllerHoldsNeitherRepositoryNorStorage() {
        for (Field field : TheoryController.class.getDeclaredFields()) {
            assertThat(FORBIDDEN)
                    .as("поле TheoryController.%s не должно быть репозиторием или хранилищем", field.getName())
                    .doesNotContain(field.getType());
        }
    }

    @Test
    void controllerIsNotEvenGivenOne() {
        for (Constructor<?> constructor : TheoryController.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes())
                    .as("репозитории и хранилище контроллеру теории не передаются")
                    .doesNotContain(FORBIDDEN.toArray(Class<?>[]::new));
        }
    }
}
