package ru.locus.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-схемы аргументов инструмента — короткими конструкторами, чтобы
 * схема читалась рядом с обработчиком, а не лежала строкой JSON.
 *
 * <p>Ровно те виды, что нужны инструментам: объект с полями, целое, число,
 * строка (в том числе из перечня значений) и список. Описания —
 * по-русски: их читает модель, а русский — ведущий язык интерфейса.
 * Порядок полей сохраняется: он виден модели и читающему тест.
 */
public final class Schema {

    private Schema() {
    }

    /** Объект с полями; обязательные поля называются при сборке. */
    public static ObjectSchema object() {
        return new ObjectSchema();
    }

    public static Map<String, Object> integer(String description) {
        return typed("integer", description);
    }

    public static Map<String, Object> number(String description) {
        return typed("number", description);
    }

    public static Map<String, Object> string(String description) {
        return typed("string", description);
    }

    /** Строка из перечня допустимых значений. */
    public static Map<String, Object> oneOf(String description, String... values) {
        Map<String, Object> schema = typed("string", description);
        schema.put("enum", List.of(values));
        return schema;
    }

    public static Map<String, Object> array(String description, Map<String, Object> items) {
        Map<String, Object> schema = typed("array", description);
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> typed(String type, String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("У поля схемы нет описания");
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }

    /** Сборка схемы объекта: поля по порядку, затем обязательные. */
    public static final class ObjectSchema {

        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private String description;

        private ObjectSchema() {
        }

        /**
         * Описание объекта — для вложенного объекта (строка сборки, Рамка),
         * у которого нет описания поля, где он стоит. Корневой схеме
         * аргументов описание не нужно: его несёт сам инструмент.
         */
        public ObjectSchema description(String description) {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Описание объекта в схеме пусто");
            }
            this.description = description;
            return this;
        }

        public ObjectSchema property(String name, Map<String, Object> schema) {
            if (properties.put(name, schema) != null) {
                throw new IllegalArgumentException("Поле «" + name + "» описано в схеме дважды");
            }
            return this;
        }

        public ObjectSchema required(String... names) {
            for (String name : names) {
                if (!properties.containsKey(name)) {
                    throw new IllegalArgumentException("Обязательное поле «" + name + "» не описано в схеме");
                }
            }
            required.addAll(Arrays.asList(names));
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            if (description != null) {
                schema.put("description", description);
            }
            schema.put("properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
            if (!required.isEmpty()) {
                schema.put("required", List.copyOf(required));
            }
            schema.put("additionalProperties", false);
            return schema;
        }
    }
}
