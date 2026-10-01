package ru.locus;

import java.util.Optional;

/**
 * Адрес возврата из параметра {@code from} (ADR-0042).
 *
 * Параметр приходит из адресной строки, то есть от кого угодно: принимается
 * только адрес внутри системы. Чужой сайт, адрес без начального «/», запись
 * вида {@code //сайт} или {@code /\сайт} (браузер читает её как чужой
 * источник), {@code javascript:} и управляющие символы отбрасываются молча:
 * ссылка «назад» тогда ведёт на раздел по умолчанию, а страница открывается
 * как обычно.
 *
 * Значение используется только как {@code href}, в HTTP-переадресацию оно
 * нигде не попадает.
 */
public final class ReturnTo {

    /** Предел длины: вложенные {@code from} растут, и неограниченный рос бы без конца. */
    static final int MAX_LENGTH = 1000;

    private ReturnTo() {
    }

    public static Optional<String> safe(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (candidate.charAt(0) != '/') {
            return Optional.empty();
        }
        if (candidate.length() > 1 && (candidate.charAt(1) == '/' || candidate.charAt(1) == '\\')) {
            return Optional.empty();
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) < 0x20 || candidate.charAt(i) == 0x7f) {
                return Optional.empty();
            }
        }
        if (candidate.contains("://")) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
