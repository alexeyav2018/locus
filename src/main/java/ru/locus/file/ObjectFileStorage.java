package ru.locus.file;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Хранилище на S3-совместимом объектном хранилище — рабочая реализация
 * (ADR-0021). Бакет приватный, наружу уходит только предподписанная ссылка.
 *
 * SDK не должен появляться нигде выше: сервисы работают с {@link FileStorage},
 * и смена поставщика стоит замены этого класса, а не правок по всему коду.
 *
 * Подпись вычисляется локально из ключа, без обращения к сервису: при показе
 * списка из полусотни задач это заметно.
 */
public class ObjectFileStorage implements FileStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration linkTtl;

    public ObjectFileStorage(S3Client client, S3Presigner presigner, String bucket, Duration linkTtl) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.linkTtl = linkTtl;
    }

    @Override
    public FileKey put(byte[] content, String contentType) {
        FileKey key = FileKey.generated(contentType);
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key.value())
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw FileStorageUnavailableException.reported("Не положить объект в бакет " + bucket, e);
        }
        return key;
    }

    @Override
    public URI temporaryLink(FileKey key) {
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(linkTtl)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key.value())
                                    .build())
                            .build())
                    .url()
                    .toURI();
        } catch (SdkException e) {
            throw FileStorageUnavailableException.reported("Не подписать ссылку на объект в бакете " + bucket, e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Хранилище вернуло неразбираемый адрес: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(FileKey key) {
        try {
            // Удаление отсутствующего объекта в S3 — успех, а не ошибка;
            // на этом же стоит требование «Повторное удаление».
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .build());
        } catch (SdkException e) {
            throw FileStorageUnavailableException.reported("Не удалить объект из бакета " + bucket, e);
        }
    }
}
