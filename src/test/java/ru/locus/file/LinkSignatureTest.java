package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Подпись ссылки: меняется от ключа, срока и секрета.
 *
 * Проверяется основание требования «Наружу файл отдаётся только по временной
 * подписанной ссылке»: подпись нельзя перенести на другой файл, срок нельзя
 * отодвинуть, не сломав её, а чужой секрет не даёт подобрать свою.
 */
class LinkSignatureTest {

    private static final Instant EXPIRES = Instant.parse("2026-09-08T12:00:00Z");

    private final LinkSignature signature = new LinkSignature("секрет");

    @Test
    void signatureDependsOnTheKey() {
        FileKey one = FileKey.generated(FileType.PDF);
        FileKey another = FileKey.generated(FileType.PDF);

        assertThat(signature.sign(one, EXPIRES)).isNotEqualTo(signature.sign(another, EXPIRES));
    }

    @Test
    void signatureDependsOnTheDeadline() {
        FileKey key = FileKey.generated(FileType.PDF);

        assertThat(signature.sign(key, EXPIRES))
                .isNotEqualTo(signature.sign(key, EXPIRES.plusSeconds(1)));
    }

    @Test
    void signatureDependsOnTheSecret() {
        FileKey key = FileKey.generated(FileType.PDF);

        assertThat(signature.sign(key, EXPIRES))
                .isNotEqualTo(new LinkSignature("другой секрет").sign(key, EXPIRES));
    }

    @Test
    void ownSignatureIsAccepted() {
        FileKey key = FileKey.generated(FileType.PDF);

        assertThat(signature.isValid(key, EXPIRES, signature.sign(key, EXPIRES))).isTrue();
    }

    @Test
    void alteredSignatureIsRejected() {
        FileKey key = FileKey.generated(FileType.PDF);
        String correct = signature.sign(key, EXPIRES);

        assertThat(signature.isValid(key, EXPIRES, correct.substring(1) + "0")).isFalse();
        assertThat(signature.isValid(key, EXPIRES.plusSeconds(3600), correct)).isFalse();
        assertThat(signature.isValid(key, EXPIRES, null)).isFalse();
        assertThat(signature.isValid(key, EXPIRES, "")).isFalse();
    }

    @Test
    void emptySecretMeansGeneratedOne() {
        FileKey key = FileKey.generated(FileType.PDF);
        LinkSignature first = new LinkSignature("");
        LinkSignature second = new LinkSignature(null);

        assertThat(first.isValid(key, EXPIRES, first.sign(key, EXPIRES))).isTrue();
        assertThat(second.isValid(key, EXPIRES, first.sign(key, EXPIRES))).isFalse();
    }
}
