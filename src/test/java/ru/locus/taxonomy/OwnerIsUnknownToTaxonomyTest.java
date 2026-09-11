package ru.locus.taxonomy;

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
 * Граница общего и личного: рубрикатор не знает, чей он.
 *
 * Рубрикатор — общая библиотека, и фильтр по владельцу к ней не применяется
 * никогда: применённый, он сделал бы её невидимой (antipatterns.md, первый
 * пункт). Проверяется не отсутствие фильтра — проверять было бы нечего, —
 * а то, что владельца сюда невозможно передать: у методов нет параметра
 * {@link UserId}, поэтому «на всякий случай отфильтровать» не получится
 * даже по недосмотру (ADR-0027).
 *
 * Тест смотрит на всю возможность, а не на один репозиторий: заведись
 * владелец в сервисе или в контроллере, он бы дошёл и до запроса.
 */
class OwnerIsUnknownToTaxonomyTest {

    private static final List<Class<?>> TAXONOMY = List.of(
            TaxonomyRepository.class,
            TaxonomyService.class,
            TaxonomyController.class,
            TaxonomyNode.class,
            TaxonomyNodeId.class,
            TaxonomyBranch.class,
            TaxonomyPath.class,
            NodeContent.class,
            TopicReceiver.class,
            TopicDistribution.class);

    @Test
    void noMethodTakesTheOwner() {
        for (Class<?> type : TAXONOMY) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("метод %s.%s не должен принимать владельца", type.getSimpleName(), method.getName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void noConstructorTakesTheOwner() {
        for (Class<?> type : TAXONOMY) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("конструктор %s не должен принимать владельца", type.getSimpleName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void nothingIsStoredAboutTheOwner() {
        Stream<Field> fields = TAXONOMY.stream().flatMap(type -> Arrays.stream(type.getDeclaredFields()));

        assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                .as("поле %s.%s не должно хранить владельца", field.getDeclaringClass().getSimpleName(),
                        field.getName())
                .isNotEqualTo(UserId.class));
    }

    /**
     * Перестройка дерева ({@code rubricator-restructure}) — единственная
     * операция рубрикатора, которая дотягивается до личного контура:
     * её продолжение в {@code mastery-marks} двинет и посчитает чужие
     * отметки Владения от имени Администратора (ADR-0034), а счёт исчезающих
     * отметок отдаёт наружу только число — без владельца и без значений.
     * Соблазн передать сюда владельца «чтобы ограничить» от этого только
     * растёт, и потому методы перестройки и счёта названы поимённо, а не
     * только покрыты общим перебором: исчезни они или переименуйся, сторож
     * упадёт, а не промолчит.
     */
    @Test
    void restructuringAndCountingTakeNoOwnerEither() {
        List<Method> restructuring = Stream.of(
                        declared(TaxonomyService.class, "create"),
                        declared(TaxonomyService.class, "deleteWithDistribution"),
                        declared(TaxonomyService.class, "countVanishingMarks"),
                        declared(TaxonomyService.class, "receiverPathsAfterRemoving"),
                        declared(NodeContent.class, "moveTopicContent"),
                        declared(NodeContent.class, "distributeTopicContent"),
                        declared(NodeContent.class, "countVanishing"))
                .flatMap(List::stream)
                .toList();

        assertThat(restructuring).allSatisfy(method -> assertThat(method.getParameterTypes())
                .as("метод перестройки %s.%s не должен принимать владельца",
                        method.getDeclaringClass().getSimpleName(), method.getName())
                .doesNotContain(UserId.class));
    }

    /** Все перегрузки: у {@code create} их две, и приёмник принимает вторая. */
    private static List<Method> declared(Class<?> type, String name) {
        List<Method> found = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
        assertThat(found)
                .as("метода %s.%s нет: перестройка переименована, и сторож её больше не видит",
                        type.getSimpleName(), name)
                .isNotEmpty();
        return found;
    }

    @Test
    void noMethodReturnsTheOwner() {
        for (Class<?> type : TAXONOMY) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("метод %s.%s не должен отдавать владельца", type.getSimpleName(), method.getName())
                        .isNotEqualTo(UserId.class);
            }
        }
    }
}
