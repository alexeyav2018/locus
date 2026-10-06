package ru.locus.upload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Обработчик принимает загрузку крупнее общего предела — до предела
 * контейнера {@code spring.servlet.multipart} (ADR-0044).
 *
 * Исключение из общего предела отмечается на методе, а не списком адресов:
 * адрес переименуют, а аннотация переедет вместе с методом
 * (design.md, «Предел загрузки»). Сегодня отмечен один обработчик —
 * загрузка исходника для сборки PDF Задачи.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LargeUpload {
}
