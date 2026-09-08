package ru.locus.file;

import org.springframework.test.context.TestPropertySource;

/**
 * Контракт хранилища на файловой реализации — той, что работает
 * при разработке.
 *
 * Настройки задаются свойствами теста, а не вторым {@code application.yaml}
 * в тестовых ресурсах: файл по тому же пути на classpath не дополняет
 * основной, а перекрывает его целиком (antipatterns.md).
 *
 * Срок жизни ссылки укорочен до двух секунд — иначе сценарий «Истёкшая
 * ссылка» проверить нечем: подождать десять минут прогон не может, а
 * подменить время у объектного хранилища нельзя, и контракт должен быть один.
 */
@TestPropertySource(properties = {
        "locus.file.storage=local",
        "locus.file.link-ttl=2s",
        "locus.file.local.directory=${java.io.tmpdir}/locus-file-storage-test",
        "locus.file.local.secret=секрет для теста"
})
class LocalFileStorageTest extends FileStorageContractTest {
}
