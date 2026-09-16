package ru.locus.work;

import ru.locus.file.FileKey;

/**
 * Файл Работы — один скан или снимок в её упорядоченном наборе.
 *
 * Своя запись с идентификатором, а не строка ключа в списке: файл
 * удаляется поштучно, и адресовать его в запросе надо без ключа хранилища —
 * ключ наружу не попадает (ADR-0021, персональные данные детей).
 * {@code position} — порядок загрузки: учитель снимает страницы по порядку.
 */
public record StudentWorkFile(StudentWorkFileId id, FileKey key, int position) {

    public StudentWorkFile {
        if (id == null) {
            throw new IllegalArgumentException("У файла Работы должен быть идентификатор");
        }
        if (key == null) {
            throw new IllegalArgumentException("У файла Работы должен быть ключ хранилища");
        }
        if (position <= 0) {
            throw new IllegalArgumentException("Позиция файла Работы должна быть положительной: " + position);
        }
    }
}
