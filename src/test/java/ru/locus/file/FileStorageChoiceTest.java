package ru.locus.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Какая реализация хранилища работает — решает настройка
 * {@code locus.file.storage}, и работает ровно одна.
 *
 * Две сразу означали бы, что часть файлов уехала не туда, куда думает
 * остальная система, а ни одной — что приложение не поднимется в тот момент,
 * когда впервые понадобится файл. Умолчание файловое: запуск в разработке
 * не должен требовать ключей от внешнего сервиса.
 */
class FileStorageChoiceTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileStorageConfiguration.class));

    @Test
    void withoutSettingsTheFileSystemImplementationWorks() {
        context.run(started -> {
            assertThat(started).hasSingleBean(LocalFileStorage.class);
            assertThat(started).doesNotHaveBean(ObjectFileStorage.class);
            assertThat(started.getBeansOfType(FileStorage.class)).hasSize(1);
        });
    }

    @Test
    void objectStorageIsChosenBySetting() {
        context.withPropertyValues(
                        "locus.file.storage=object",
                        "locus.file.object.endpoint=http://localhost:9000",
                        "locus.file.object.region=ru-central1",
                        "locus.file.object.bucket=locus",
                        "locus.file.object.access-key=ключ",
                        "locus.file.object.secret-key=секрет")
                .run(started -> {
                    assertThat(started).hasSingleBean(ObjectFileStorage.class);
                    assertThat(started).doesNotHaveBean(LocalFileStorage.class);
                    assertThat(started.getBeansOfType(FileStorage.class)).hasSize(1);
                });
    }

    @Test
    void fileSystemStorageIsChosenBySettingToo() {
        context.withPropertyValues("locus.file.storage=local")
                .run(started -> {
                    assertThat(started).hasSingleBean(LocalFileStorage.class);
                    assertThat(started.getBeansOfType(FileStorage.class)).hasSize(1);
                });
    }
}
