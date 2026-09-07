package ru.locus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Версия образа PostgreSQL берётся из compose.yaml — того же файла, по которому
 * поднимается база разработки. Одно место, а не два: разъехавшиеся версии
 * тихая ошибка, при которой тесты зелёные, а на боевой базе поведение другое.
 */
final class ObrazPostgres {

    private static final Path COMPOSE = Path.of("compose.yaml");

    private ObrazPostgres() {
    }

    @SuppressWarnings("unchecked")
    static String izComposeFile() {
        try (InputStream potok = Files.newInputStream(COMPOSE)) {
            Map<String, Object> koren = new Yaml().load(potok);
            Map<String, Object> servisy = (Map<String, Object>) koren.get("services");
            Map<String, Object> postgres = servisy == null ? null : (Map<String, Object>) servisy.get("postgres");
            Object obraz = postgres == null ? null : postgres.get("image");
            if (obraz == null) {
                throw new IllegalStateException(
                        "В " + COMPOSE.toAbsolutePath() + " нет services.postgres.image");
            }
            return obraz.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Не прочитать " + COMPOSE.toAbsolutePath(), e);
        }
    }
}
