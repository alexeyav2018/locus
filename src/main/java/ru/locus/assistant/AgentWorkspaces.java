package ru.locus.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.FileSystemUtils;
import ru.locus.user.UserId;

/**
 * Рабочие места Помощника — своё у каждого учителя (спека {@code assistant-tools},
 * «Рабочие места учителей разные»; design.md agent-runner, Р7).
 *
 * <p>Под корнем у учителя два каталога: {@code <id>/work/} — рабочий каталог
 * процесса Claude Code, куда перед каждой Репликой заново копируется
 * {@code classpath:agent/**} (правила, навыки, {@code .mcp.json}, разрешения),
 * и {@code <id>/config/} — его {@code CLAUDE_CONFIG_DIR} с сеансами, который
 * Локус только создаёт и не трогает. Очистка {@code work/} перед копированием
 * убирает файлы, удалённые из {@code agent/} новой версией; путь при этом
 * не меняется, и сеанс прошлой Реплики возобновляется.
 *
 * <p>Чужой сеанс через файлы не возобновить: у каждого учителя свой каталог
 * настроек, а идентификатор сеанса выдаёт только свой Разговор.
 */
public class AgentWorkspaces {

    /** Каталог ресурсов рабочего места в сборке ({@code pom.xml}, {@code targetPath}). */
    static final String SOURCE = "agent/";

    private final Path root;
    private final ResourcePatternResolver resources;

    public AgentWorkspaces(Path root) {
        this(root, new PathMatchingResourcePatternResolver());
    }

    AgentWorkspaces(Path root, ResourcePatternResolver resources) {
        this.root = root.toAbsolutePath().normalize();
        this.resources = resources;
    }

    /** Рабочий каталог процесса и каталог настроек Claude Code одного учителя. */
    public record Workspace(Path work, Path config) {
    }

    /**
     * Развернуть рабочее место учителя к Реплике: {@code work/} — свежая копия
     * {@code agent/}, {@code config/} — существует.
     */
    public Workspace prepare(UserId user) {
        Path home = root.resolve(Long.toString(user.value()));
        Path work = home.resolve("work");
        Path config = home.resolve("config");
        try {
            FileSystemUtils.deleteRecursively(work);
            Files.createDirectories(work);
            Files.createDirectories(config);
            copySourceInto(work);
        } catch (IOException e) {
            throw new UncheckedIOException("Рабочее место Помощника не развернулось: " + home, e);
        }
        return new Workspace(work, config);
    }

    private void copySourceInto(Path work) throws IOException {
        URL base = getClass().getClassLoader().getResource(SOURCE);
        if (base == null) {
            throw new IllegalStateException("В сборке нет рабочего места Помощника: classpath:" + SOURCE);
        }
        String prefix = base.toString();
        for (Resource resource : resources.getResources("classpath:" + SOURCE + "**")) {
            String url = resource.getURL().toString();
            if (!url.startsWith(prefix) || url.endsWith("/") || !resource.isReadable()) {
                continue;
            }
            Path target = work.resolve(decoded(url.substring(prefix.length()))).normalize();
            if (!target.startsWith(work)) {
                throw new IllegalStateException("Файл рабочего места вне его каталога: " + url);
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Путь в адресе ресурса закодирован: пробел и кириллица в имени навыка — {@code %XX}. */
    private static String decoded(String relative) {
        return URLDecoder.decode(relative.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
