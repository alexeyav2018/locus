package ru.locus.file;

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
 * Граница общего и личного: хранилище не знает, чьи файлы.
 *
 * Тестов на фильтр по владельцу в этом изменении нет, и это сказано
 * сознательно: ни личных данных, ни репозиториев оно не заводит, фильтровать
 * нечего. Проверяется другое — что владелец сюда и не попал. Появись
 * {@link UserId} в сигнатуре хранилища, у него завелось бы мнение о правах,
 * а решать их должен сервис, который выдаёт ссылку (ADR-0027): в двух местах
 * это правило разъезжается молча.
 */
class OwnerIsUnknownToStorageTest {

    private static final List<Class<?>> STORAGE = List.of(
            FileStorage.class,
            LocalFileStorage.class,
            ObjectFileStorage.class,
            FileController.class,
            FileKey.class,
            ImageCompression.class,
            LinkSignature.class);

    @Test
    void noMethodTakesTheOwner() {
        for (Class<?> type : STORAGE) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("метод %s.%s не должен принимать владельца", type.getSimpleName(), method.getName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void noConstructorTakesTheOwner() {
        for (Class<?> type : STORAGE) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("конструктор %s не должен принимать владельца", type.getSimpleName())
                        .doesNotContain(UserId.class);
            }
        }
    }

    @Test
    void nothingIsStoredAboutTheOwner() {
        Stream<Field> fields = STORAGE.stream().flatMap(type -> Arrays.stream(type.getDeclaredFields()));

        assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                .as("поле %s.%s не должно хранить владельца", field.getDeclaringClass().getSimpleName(),
                        field.getName())
                .isNotEqualTo(UserId.class));
    }

    @Test
    void noMethodReturnsTheOwner() {
        for (Class<?> type : STORAGE) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("метод %s.%s не должен отдавать владельца", type.getSimpleName(), method.getName())
                        .isNotEqualTo(UserId.class);
            }
        }
    }
}
