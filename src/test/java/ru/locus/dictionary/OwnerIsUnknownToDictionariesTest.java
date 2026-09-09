package ru.locus.dictionary;

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
 * Граница общего и личного: словари не знают, чьи они.
 *
 * Словари Методов и Характеристик — общая библиотека, и фильтр по владельцу
 * к ней не применяется никогда: применённый, он сделал бы её невидимой,
 * а разметку задачи — невозможной (antipatterns.md, первый пункт).
 * Проверяется не отсутствие фильтра — проверять было бы нечего, — а то,
 * что владельца сюда невозможно передать: у методов нет параметра
 * {@link UserId}, поэтому «на всякий случай отфильтровать» не получится
 * даже по недосмотру (ADR-0027).
 *
 * Тест смотрит на всю возможность, а не на репозитории: заведись владелец
 * в сервисе или в контроллере, он бы дошёл и до запроса.
 */
class OwnerIsUnknownToDictionariesTest {

    private static final List<Class<?>> DICTIONARIES = List.of(
            SolutionMethodRepository.class,
            CharacteristicRepository.class,
            SolutionMethodService.class,
            CharacteristicService.class,
            DictionaryController.class,
            SolutionMethod.class,
            SolutionMethodId.class,
            Characteristic.class,
            CharacteristicId.class);

    @Test
    void noMethodTakesTheOwner() {
        for (Class<?> type : DICTIONARIES) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("метод %s.%s не должен принимать владельца", type.getSimpleName(), method.getName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void noConstructorTakesTheOwner() {
        for (Class<?> type : DICTIONARIES) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("конструктор %s не должен принимать владельца", type.getSimpleName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void nothingIsStoredAboutTheOwner() {
        Stream<Field> fields = DICTIONARIES.stream().flatMap(type -> Arrays.stream(type.getDeclaredFields()));

        assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                .as("поле %s.%s не должно хранить владельца", field.getDeclaringClass().getSimpleName(),
                        field.getName())
                .isNotEqualTo(UserId.class));
    }

    @Test
    void noMethodReturnsTheOwner() {
        for (Class<?> type : DICTIONARIES) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("метод %s.%s не должен отдавать владельца", type.getSimpleName(), method.getName())
                        .isNotEqualTo(UserId.class);
            }
        }
    }
}
