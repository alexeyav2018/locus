package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.locus.user.UserId;

/**
 * Граница общего и личного: библиотека Задач не знает, чья она.
 *
 * Задачи — общая библиотека, и фильтр по владельцу к ней не применяется
 * никогда: применённый, он сделал бы её невидимой, а выдачу заданий —
 * невозможной (antipatterns.md, первый пункт). Проверяется не отсутствие
 * фильтра — проверять было бы нечего, — а то, что владельца сюда невозможно
 * передать: у методов нет параметра {@link UserId}, поэтому «на всякий случай
 * отфильтровать» не получится даже по недосмотру (ADR-0027).
 *
 * Тест смотрит на всю возможность, а не на один репозиторий: заведись
 * владелец в сервисе или в контроллере, он бы дошёл и до запроса.
 */
class OwnerIsUnknownToProblemsTest {

    private static final List<Class<?>> PROBLEMS = List.of(
            ProblemRepository.class,
            ProblemService.class,
            ProblemController.class,
            ProblemSearchController.class,
            Problem.class,
            ProblemId.class,
            ProblemFilter.class,
            MatchMode.class,
            FoundProblem.class,
            UploadedFile.class,
            ProblemsOnNode.class,
            ProblemMarkupUsage.class);

    @Test
    void noMethodTakesTheOwner() {
        for (Class<?> type : PROBLEMS) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("метод %s.%s не должен принимать владельца", type.getSimpleName(), method.getName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void noConstructorTakesTheOwner() {
        for (Class<?> type : PROBLEMS) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("конструктор %s не должен принимать владельца", type.getSimpleName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void nothingIsStoredAboutTheOwner() {
        Stream<Field> fields = PROBLEMS.stream().flatMap(type -> Arrays.stream(type.getDeclaredFields()));

        assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                .as("поле %s.%s не должно хранить владельца", field.getDeclaringClass().getSimpleName(),
                        field.getName())
                .isNotEqualTo(UserId.class));
    }

    @Test
    void noMethodReturnsTheOwner() {
        for (Class<?> type : PROBLEMS) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("метод %s.%s не должен отдавать владельца", type.getSimpleName(), method.getName())
                        .isNotEqualTo(UserId.class);
            }
        }
    }

    /**
     * Перестройка дерева ({@code rubricator-restructure}) — единственная
     * операция, которая действует и на личный контур: её продолжение
     * в {@code mastery-marks} двинет и посчитает чужие отметки от имени
     * Администратора (ADR-0034). Соблазн передать сюда владельца
     * «чтобы ограничить» от этого только растёт — и потому методы
     * перестройки названы поимённо, а не только покрыты общим перебором:
     * исчезни они или переименуйся, сторож упадёт, а не промолчит.
     */
    @Test
    void restructuringTakesNoOwnerEither() {
        List<Method> restructuring = Stream.of(
                        declared(ProblemRepository.class, "replaceTopic"),
                        declared(ProblemRepository.class, "replaceTopicFor"),
                        declared(ProblemService.class, "rehomeTopic"),
                        declared(ProblemService.class, "distribute"))
                .toList();

        assertThat(restructuring).allSatisfy(method -> assertThat(method.getParameterTypes())
                .as("метод перестройки %s не должен принимать владельца", method.getName())
                .doesNotContain(UserId.class));
    }

    private static Method declared(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "метода " + type.getSimpleName() + "." + name + " нет: перестройка переименована, "
                                + "и сторож её больше не видит"));
    }
}
