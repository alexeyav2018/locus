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
public final class PostgresImage {

    private static final Path COMPOSE = Path.of("compose.yaml");

    private PostgresImage() {
    }

    @SuppressWarnings("unchecked")
    public static String fromComposeFile() {
        try (InputStream stream = Files.newInputStream(COMPOSE)) {
            Map<String, Object> root = new Yaml().load(stream);
            Map<String, Object> services = (Map<String, Object>) root.get("services");
            Map<String, Object> postgres = services == null ? null : (Map<String, Object>) services.get("postgres");
            Object image = postgres == null ? null : postgres.get("image");
            if (image == null) {
                throw new IllegalStateException(
                        "В " + COMPOSE.toAbsolutePath() + " нет services.postgres.image");
            }
            return image.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Не прочитать " + COMPOSE.toAbsolutePath(), e);
        }
    }
}
