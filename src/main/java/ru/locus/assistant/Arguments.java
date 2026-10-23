package ru.locus.assistant;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Аргументы вызова инструмента, как они пришли из JSON запроса: отображение
 * «имя → значение», где числа — {@link Number} любого вида, строки —
 * {@link String}, списки — {@link List}, объекты — {@link Map}.
 *
 * <p>Разбор здесь, а не в каждом инструменте: число из JSON приходит
 * то {@code Integer}, то {@code Long}, то {@code Double} — в зависимости
 * от величины и от того, как его записала модель, — и приведение,
 * размноженное по десяти инструментам, разъехалось бы в деталях.
 * Отсутствующий или не того типа аргумент — {@link IllegalArgumentException},
 * называющая аргумент по имени: сервер отдаёт её модели отказом, и та
 * может поправить вызов.
 *
 * <p>Строка, в которой записано число, принимается там, где ждут число:
 * модель иногда шлёт {@code "5"} вместо {@code 5}, и отказывать за это
 * незачем — величина та же.
 */
public final class Arguments {

    private final Map<String, Object> values;

    public Arguments(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static Arguments of(Map<String, Object> values) {
        return new Arguments(values);
    }

    public static Arguments none() {
        return new Arguments(Map.of());
    }

    /** Есть ли аргумент с таким именем (значение {@code null} — нет). */
    public boolean has(String name) {
        return values.get(name) != null;
    }

    /** Целое число; отсутствие — отказ. */
    public long requireLong(String name) {
        return optionalLong(name).orElseThrow(() -> missing(name));
    }

    /** Целое число, если оно передано. */
    public Optional<Long> optionalLong(String name) {
        Object value = values.get(name);
        return value == null ? Optional.empty() : Optional.of(asLong(name, value));
    }

    /** Целое число в пределах {@code int}; отсутствие — отказ. */
    public int requireInt(String name) {
        return optionalInt(name).orElseThrow(() -> missing(name));
    }

    /** Целое число в пределах {@code int}, если оно передано. */
    public Optional<Integer> optionalInt(String name) {
        return optionalLong(name).map(value -> {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Аргумент «" + name + "» слишком велик: " + value);
            }
            return value.intValue();
        });
    }

    /** Число с дробной частью; отсутствие — отказ. */
    public double requireDouble(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw missing(name);
        }
        return asDouble(name, value);
    }

    /** Список целых чисел; отсутствующий аргумент — пустой список. */
    public List<Long> longs(String name) {
        return list(name).stream().map(item -> asLong(name, item)).toList();
    }

    /** Непустая строка; отсутствие — отказ. */
    public String requireString(String name) {
        return optionalString(name).orElseThrow(() -> missing(name));
    }

    /** Строка, если она передана и не пуста. */
    public Optional<String> optionalString(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Аргумент «" + name + "» должен быть строкой");
        }
        return string.isBlank() ? Optional.empty() : Optional.of(string);
    }

    /** Значение перечисления по имени; отсутствие — отказ. */
    public <E extends Enum<E>> E requireEnum(String name, Class<E> type) {
        return optionalEnum(name, type).orElseThrow(() -> missing(name));
    }

    /**
     * Значение перечисления по имени, если оно передано. Регистр не важен:
     * {@code all} и {@code ALL} — одно значение, схема инструмента вправе
     * предлагать имена строчными; чужое имя — отказ, перечисляющий
     * допустимые.
     */
    public <E extends Enum<E>> Optional<E> optionalEnum(String name, Class<E> type) {
        return optionalString(name).map(value -> {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(value.strip())) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("Аргумент «" + name + "» должен быть одним из "
                    + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))
                    + ", а не «" + value + "»");
        });
    }

    /** Вложенный объект; отсутствие — отказ. */
    public Arguments requireObject(String name) {
        return optionalObject(name).orElseThrow(() -> missing(name));
    }

    /** Вложенный объект, если он передан. */
    public Optional<Arguments> optionalObject(String name) {
        Object value = values.get(name);
        return value == null ? Optional.empty() : Optional.of(asObject(name, value));
    }

    /** Список вложенных объектов; отсутствующий аргумент — пустой список. */
    public List<Arguments> objects(String name) {
        return list(name).stream().map(item -> asObject(name, item)).toList();
    }

    private List<?> list(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Аргумент «" + name + "» должен быть списком");
        }
        return list;
    }

    private static long asLong(String name, Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number number) {
            double real = number.doubleValue();
            if (real != Math.rint(real) || Double.isInfinite(real)) {
                throw new IllegalArgumentException("Аргумент «" + name + "» должен быть целым числом, а не " + value);
            }
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Аргумент «" + name + "» должен быть целым числом, а не «"
                        + string + "»");
            }
        }
        throw new IllegalArgumentException("Аргумент «" + name + "» должен быть целым числом");
    }

    private static double asDouble(String name, Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Аргумент «" + name + "» должен быть числом, а не «"
                        + string + "»");
            }
        }
        throw new IllegalArgumentException("Аргумент «" + name + "» должен быть числом");
    }

    @SuppressWarnings("unchecked")
    private static Arguments asObject(String name, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Аргумент «" + name + "» должен быть объектом");
        }
        return new Arguments((Map<String, Object>) map);
    }

    private static IllegalArgumentException missing(String name) {
        return new IllegalArgumentException("Аргумент «" + name + "» обязателен");
    }
}
