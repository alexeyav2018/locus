package ru.locus.file;

import java.net.URI;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MinIOContainer;
import ru.locus.MinioImage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Тот же контракт — на объектном хранилище, единственной реализации, которая
 * пойдёт в бой. Проверяется на MinIO в контейнере: подпись, срок и закрытость
 * бакета должны работать по-настоящему, а не в подмене.
 *
 * Режима «нет Docker — пропустить» здесь нет, как и у остальных
 * интеграционных тестов: молча пропущенная проверка хуже отсутствующей
 * (ADR-0023).
 */
@TestPropertySource(properties = {
        "locus.file.storage=object",
        "locus.file.link-ttl=2s"
})
class ObjectFileStorageTest extends FileStorageContractTest {

    private static final String BUCKET = "locus-test";
    private static final String REGION = "ru-central1";

    private static final MinIOContainer MINIO = new MinIOContainer(MinioImage.name());

    static {
        MINIO.start();
        createBucket();
    }

    @DynamicPropertySource
    static void objectStorageSettings(DynamicPropertyRegistry registry) {
        registry.add("locus.file.object.endpoint", MINIO::getS3URL);
        registry.add("locus.file.object.region", () -> REGION);
        registry.add("locus.file.object.bucket", () -> BUCKET);
        registry.add("locus.file.object.access-key", MINIO::getUserName);
        registry.add("locus.file.object.secret-key", MINIO::getPassword);
    }

    /**
     * Бакет заводит администратор хранилища, а не приложение: право на его
     * создание боевым ключам не нужно. В тесте эту роль исполняет сам тест.
     */
    private static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }
}
