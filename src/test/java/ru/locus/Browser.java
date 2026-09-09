package ru.locus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Посетитель системы: настоящий HTTP, свои cookie на весь сеанс и токен CSRF,
 * взятый из разметки, как его взял бы браузер.
 *
 * Подмены безопасности здесь нет намеренно: половина проверяемого в этом
 * изменении живёт в цепочке фильтров, и тест, обошедший её, проверил бы
 * не то, чем пользуются люди.
 *
 * Переадресации не выполняются автоматически — за ними и наблюдают тесты
 * («обращение приводит к форме входа»).
 */
public final class Browser {

    /** Токен CSRF Thymeleaf подставляет в форму скрытым полем. */
    private static final Pattern CSRF = Pattern.compile(
            "name=\"_csrf\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"_csrf\"");

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;

    public Browser(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public record Page(int status, String contentType, String body, String location) {

        public boolean redirectsTo(String path) {
            return (status == 302 || status == 303) && location != null && location.endsWith(path);
        }
    }

    public Page get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    /** Переходит по адресу и, если ответ — переадресация, идёт по ней. */
    public Page follow(String path) {
        Page page = get(path);
        return page.location() == null ? page : get(relative(page.location()));
    }

    public Page postForm(String action, Map<String, String> fields) {
        Map<String, String> withToken = new LinkedHashMap<>(fields);
        withToken.put("_csrf", csrfTokenFrom(action));
        return send(HttpRequest.newBuilder(URI.create(baseUrl + action))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncoded(withToken), StandardCharsets.UTF_8))
                .build());
    }

    /**
     * Отправка формы с файлами — тем же multipart, каким её шлёт браузер.
     *
     * Своя сборка тела, а не библиотека: части здесь две-три, а подмена
     * отправки означала бы, что разбор запроса приложением никто не проверял.
     *
     * @param files имя поля -> пара «имя файла, содержимое»; пустое содержимое
     *              означает незаполненное поле выбора файла
     */
    public Page postMultipart(String action, Map<String, String> fields, Map<String, byte[]> files) {
        String boundary = "----locus" + java.util.UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        Map<String, String> withToken = new LinkedHashMap<>(fields);
        withToken.put("_csrf", csrfTokenFrom(action));

        withToken.forEach((name, value) -> write(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n"));
        files.forEach((name, content) -> {
            write(body, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name + ".pdf\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n");
            write(body, content);
            write(body, "\r\n");
        });
        write(body, "--" + boundary + "--\r\n");

        return send(HttpRequest.newBuilder(URI.create(baseUrl + action))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build());
    }

    /** Содержимое по ссылке — байтами, как их получил бы браузер. */
    public byte[] getBytes(String path) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? response.body() : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void write(java.io.ByteArrayOutputStream body, String text) {
        write(body, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(java.io.ByteArrayOutputStream body, byte[] bytes) {
        body.writeBytes(bytes);
    }

    /** Вход формой — ровно так, как его выполняет человек. */
    public Page logIn(String login, String password) {
        return postForm("/login", Map.of("username", login, "password", password));
    }

    public Page logOut() {
        return postForm("/logout", Map.of());
    }

    /**
     * Берёт токен CSRF со страницы, на которой стоит форма. Токен привязан
     * к сеансу, поэтому подходит любая форма; если по адресу отправки формы
     * страницы нет, берётся форма входа.
     */
    private String csrfTokenFrom(String action) {
        return token(get(action).body())
                .or(() -> token(follow(action).body()))
                .or(() -> token(follow("/login").body()))
                .orElseThrow(() -> new IllegalStateException("На странице " + action + " нет токена CSRF"));
    }

    private static Optional<String> token(String html) {
        if (html == null) {
            return Optional.empty();
        }
        Matcher matcher = CSRF.matcher(html);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    private Page send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Page(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.body(),
                    response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String relative(String location) {
        return location.startsWith(baseUrl) ? location.substring(baseUrl.length()) : location;
    }

    private static String urlEncoded(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        fields.forEach((name, value) -> {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return body.toString();
    }
}
