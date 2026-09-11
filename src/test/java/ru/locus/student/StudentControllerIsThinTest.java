package ru.locus.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;

/**
 * Требование «Контроллеры тонкие» (standards.md, «Слои и границы») для
 * первой области личного контура — и его усиление владельцем.
 *
 * Разделение слоёв держится не на расположении папок, а на том, что
 * контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверки прав, и фильтр по владельцу.
 *
 * Владельца контроллер не знает вовсе (ADR-0027): ни один обработчик
 * не принимает {@link UserId}, и {@link CurrentUser} контроллерам
 * не передаётся — значит, {@code CurrentUser.id()} им звать нечем.
 * Владельца подставляет сервис; появись он в контроллере — это был бы
 * путь назначить владельцем другого или прочитать чужое.
 */
class StudentControllerIsThinTest {

    private static final List<Class<?>> CONTROLLERS =
            List.of(StudentController.class, GroupController.class);

    private static final List<Class<?>> REPOSITORIES =
            List.of(StudentRepository.class, GroupRepository.class);

    @Test
    void controllersDoNotHoldARepositoryOrTheCurrentUser() {
        for (Class<?> controller : CONTROLLERS) {
            for (Field field : controller.getDeclaredFields()) {
                assertThat(REPOSITORIES)
                        .as("%s: поле %s не должно быть репозиторием",
                                controller.getSimpleName(), field.getName())
                        .doesNotContain(field.getType());
                assertThat(field.getType())
                        .as("%s: поле %s — контроллер о вошедшем не знает",
                                controller.getSimpleName(), field.getName())
                        .isNotEqualTo(CurrentUser.class);
            }
        }
    }

    @Test
    void controllersAreNotEvenGivenARepositoryOrTheCurrentUser() {
        for (Class<?> controller : CONTROLLERS) {
            for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("%s: репозитории и CurrentUser контроллеру не передаются",
                                controller.getSimpleName())
                        .doesNotContain(StudentRepository.class, GroupRepository.class, CurrentUser.class);
            }
        }
    }

    @Test
    void noHandlerAcceptsAnOwner() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("%s.%s: владелец из запроса не читается",
                                controller.getSimpleName(), method.getName())
                        .doesNotContain(UserId.class);
            }
        }
    }
}
