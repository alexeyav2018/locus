package ru.locus.work;

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
 * Работ — и его усиление владельцем, как у Заданий
 * ({@code AssignmentControllerIsThinTest}).
 *
 * Контроллер не ходит в репозиторий мимо сервиса: пойди он туда — вместе
 * с запросом мимо сервиса ушли бы и проверка роли, и фильтр по владельцу,
 * и укладка файлов с уборкой. Хранилище ему тоже не передаётся: ключ
 * файла в контроллере — путь к постоянному адресу, которого у скана
 * быть не должно (ADR-0021).
 *
 * Владельца контроллер не знает вовсе (ADR-0027): ни один обработчик
 * не принимает {@link UserId}, и {@link CurrentUser} контроллеру
 * не передаётся.
 *
 * Те же правила — для {@link AssignmentScreen}: он не контроллер,
 * но собирает модель экрана приёма для двух контроллеров, и лазейка
 * к репозиторию в нём была бы лазейкой в обоих.
 */
class StudentWorkControllerIsThinTest {

    private static final List<Class<?>> CONTROLLERS =
            List.of(StudentWorkController.class, AssignmentScreen.class);

    private static final List<Class<?>> FORBIDDEN =
            List.of(StudentWorkRepository.class, ru.locus.file.FileStorage.class, CurrentUser.class);

    @Test
    void controllerDoesNotHoldARepositoryStorageOrTheCurrentUser() {
        for (Class<?> controller : CONTROLLERS) {
            for (Field field : controller.getDeclaredFields()) {
                assertThat(FORBIDDEN)
                        .as("%s: поле %s — ни репозиторий, ни хранилище, ни вошедший",
                                controller.getSimpleName(), field.getName())
                        .doesNotContain(field.getType());
            }
        }
    }

    @Test
    void controllerIsNotEvenGivenARepositoryStorageOrTheCurrentUser() {
        for (Class<?> controller : CONTROLLERS) {
            for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("%s: репозиторий, хранилище и CurrentUser контроллеру не передаются",
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
