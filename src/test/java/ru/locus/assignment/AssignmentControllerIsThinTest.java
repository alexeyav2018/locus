package ru.locus.assignment;

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
 * Заданий — и его усиление владельцем, как у Учеников
 * ({@code StudentControllerIsThinTest}).
 *
 * Контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверка роли, и фильтр по владельцу,
 * и проверка «нет Работы» перед удалением.
 *
 * Владельца контроллер не знает вовсе (ADR-0027): ни один обработчик
 * не принимает {@link UserId}, и {@link CurrentUser} контроллеру
 * не передаётся — значит, {@code CurrentUser.id()} ему звать нечем.
 * Владельца подставляет сервис; появись он в контроллере — это был бы
 * путь выдать Задание от чужого имени или прочитать чужое.
 */
class AssignmentControllerIsThinTest {

    private static final List<Class<?>> CONTROLLERS = List.of(AssignmentController.class);

    private static final List<Class<?>> REPOSITORIES =
            List.of(AssignmentRepository.class, AssignmentBatchRepository.class);

    @Test
    void controllerDoesNotHoldARepositoryOrTheCurrentUser() {
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
    void controllerIsNotEvenGivenARepositoryOrTheCurrentUser() {
        for (Class<?> controller : CONTROLLERS) {
            for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("%s: репозитории и CurrentUser контроллеру не передаются",
                                controller.getSimpleName())
                        .doesNotContain(AssignmentRepository.class, AssignmentBatchRepository.class,
                                CurrentUser.class);
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
