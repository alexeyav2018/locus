package ru.locus.file;

import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Какая реализация хранилища работает — решает настройка
 * {@code locus.file.storage}. Умолчание файловое: запуск в разработке
 * не должен требовать ключей от внешнего сервиса, иначе появляется шаг,
 * который легко забыть.
 *
 * Реализация ровно одна: сервисы видят интерфейс и о выборе не знают.
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "locus.file.storage", havingValue = FileStorageProperties.LOCAL,
            matchIfMissing = true)
    public LocalFileStorage localFileStorage(FileStorageProperties properties) {
        return new LocalFileStorage(
                properties.local().directory(),
                new LinkSignature(properties.local().secret()),
                properties.linkTtl(),
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "locus.file.storage", havingValue = FileStorageProperties.OBJECT)
    public ObjectFileStorage objectFileStorage(FileStorageProperties properties) {
        FileStorageProperties.ObjectStorage settings = properties.object();
        AwsCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(settings.accessKey(), settings.secretKey()));
        URI endpoint = URI.create(settings.endpoint());
        Region region = Region.of(settings.region());

        // Путь в адресе, а не имя бакета в имени хоста: так работает MinIO
        // в тестах, и так же принимает Яндекс. Виртуальные хосты потребовали бы
        // рабочего DNS под каждый бакет — в тестах его взять негде.
        S3Client client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        return new ObjectFileStorage(client, presigner, settings.bucket(), properties.linkTtl());
    }

    @Bean
    public ImageCompression imageCompression(FileStorageProperties properties) {
        return new ImageCompression(properties.image().maxSide(), properties.image().quality());
    }
}
