package ru.locus.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подпись ссылки для файловой реализации хранилища.
 *
 * Объектное хранилище подписывает ссылку своими средствами; чтобы поведение
 * обоих вариантов совпадало, папка на диске подписывает свои ссылки сама.
 * Иначе требование «прямой доступ без подписи не работает» проверялось бы
 * только у одного варианта — того, что в бою не используется.
 *
 * Подписывается пара «ключ + срок»: подменив любую из частей, подпись
 * подобрать нельзя, а срок нельзя отодвинуть, не сломав её.
 */
public class LinkSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    /**
     * Пустая настройка означает секрет, сгенерированный при старте: ссылки
     * не переживают перезапуск. Для разработки это приемлемо — в бою работает
     * объектное хранилище, подписывающее своим ключом.
     */
    public LinkSignature(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String sign(FileKey key, Instant expires) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            String signed = key.value() + "|" + expires.getEpochSecond();
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Не подписать ссылку: " + e.getMessage(), e);
        }
    }

    /**
     * Сравнение идёт временем, не зависящим от совпадающего префикса:
     * обычное {@code equals} по подписи подсказывает подбирающему, сколько
     * первых символов он уже угадал.
     */
    public boolean isValid(FileKey key, Instant expires, String signature) {
        if (signature == null) {
            return false;
        }
        byte[] expected = sign(key, expires).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
