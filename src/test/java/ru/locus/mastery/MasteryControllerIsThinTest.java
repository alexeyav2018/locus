package ru.locus.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.locus.user.CurrentUser;
import ru.locus.user.UserId;
import ru.locus.work.StudentWorkRepository;

/**
 * Требование «Контроллеры тонкие» (standards.md, «Слои и границы») для
 * отметок Владения — и его усиление владельцем, как у Работ
 * ({@code StudentWorkControllerIsThinTest}).
 *
 * Контроллер не ходит в репозиторий мимо сервиса — ни в свой, ни
 * в репозиторий Работ, из которого сервис читает, принята ли Работа:
 * пойди он туда — вместе с запросом мимо сервиса ушли бы проверка роли,
 * фильтр по владельцу и проверка «пара из разметки Задачи», а с ней
 * и инвариант 4 «ячейка порождается употреблением».
 *
 * Владельца контроллер не знает вовсе (ADR-0027): ни один обработчик
 * не принимает {@link UserId}, и {@link CurrentUser} контроллеру
 * не передаётся.
 */
class MasteryControllerIsThinTest {

    private static final List<Class<?>> CONTROLLERS = List.of(MasteryController.class);

    private static final List<Class<?>> FORBIDDEN =
            List.of(MasteryRepository.class, StudentWorkRepository.class, CurrentUser.class);

    @Test
    void controllerDoesNotHoldARepositoryOrTheCurrentUser() {
        for (Class<?> controller : CONTROLLERS) {
            for (Field field : controller.getDeclaredFields()) {
                assertThat(FORBIDDEN)
                        .as("%s: поле %s — ни репозиторий, ни вошедший",
                                controller.getSimpleName(), field.getName())
                        .doesNotContain(field.getType());
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
                        .doesNotContainAnyElementsOf(FORBIDDEN);
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
