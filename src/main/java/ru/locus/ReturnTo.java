package ru.locus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Адрес возврата из параметра {@code from} (ADR-0042, ADR-0060).
 *
 * Параметр приходит из адресной строки, то есть от кого угодно: принимается
 * только адрес внутри системы. Чужой сайт, адрес без начального «/», запись
 * вида {@code //сайт} или {@code /\сайт} (браузер читает её как чужой
 * источник), {@code javascript:} и управляющие символы отбрасываются молча:
 * ссылка «назад» тогда ведёт на раздел по умолчанию, а страница открывается
 * как обычно.
 *
 * Значение служит ссылкой «назад», дописывается к адресу действия, чтобы
 * пережить его ({@link #carried}), и лишь в одном случае — когда следующей
 * страницы нет — становится переадресацией ({@link #location}). Во всех
 * трёх случаях — только прошедшее {@link #safe}.
 */
public final class ReturnTo {

    /** Предел длины: вложенные {@code from} растут, и неограниченный рос бы без конца. */
    static final int MAX_LENGTH = 1000;

    /** Что идёт в адрес как есть: незарезервированное, разделители и {@code %} уже закодированного. */
    private static final String ALLOWED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#@!$&'()*+,;=%";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

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

    /**
     * Адрес действия с возвратом (ADR-0060): страница несёт свой {@code from}
     * через формы, и переадресация после действия — тоже. Непроверенный
     * {@code from} не дописывается; якорь остаётся последним.
     *
     * @param address адрес внутри системы, может нести запрос и якорь
     * @param from    присланный возврат, может быть {@code null}
     */
    public static String carried(String address, String from) {
        Optional<String> back = safe(from);
        if (back.isEmpty()) {
            return address;
        }
        int anchor = address.indexOf('#');
        String path = anchor < 0 ? address : address.substring(0, anchor);
        String tail = anchor < 0 ? "" : address.substring(anchor);
        return path + (path.contains("?") ? "&" : "?") + "from="
                + URLEncoder.encode(back.get(), StandardCharsets.UTF_8) + tail;
    }

    /**
     * Проверенный адрес для переадресации прямо по {@code from} (ADR-0060):
     * недопустимое в адресе — кириллица, пробел, фигурные скобки, кавычки —
     * кодируется, уже закодированное ({@code %2F} вложенного возврата)
     * остаётся как есть. Иначе сырое ушло бы в {@code Location}, а скобки
     * Spring прочёл бы переменной шаблона адреса.
     *
     * @param safe адрес, прошедший {@link #safe}
     */
    public static String location(String safe) {
        StringBuilder encoded = new StringBuilder(safe.length());
        for (byte octet : safe.getBytes(StandardCharsets.UTF_8)) {
            char symbol = (char) (octet & 0xff);
            if (symbol < 0x80 && ALLOWED.indexOf(symbol) >= 0) {
                encoded.append(symbol);
            } else {
                encoded.append('%').append(HEX[(octet >> 4) & 0xf]).append(HEX[octet & 0xf]);
            }
        }
        return encoded.toString();
    }
}
