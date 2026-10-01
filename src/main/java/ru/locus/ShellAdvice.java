package ru.locus;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;
import ru.locus.user.User;

/**
 * Данные для общей шапки страниц: кто вошёл, какие разделы ему показать
 * и какой из них сейчас открыт.
 *
 * Шапка одна на все экраны ({@code templates/fragments/shell.html}), поэтому
 * данные для неё берутся здесь, а не повторяются в каждом контроллере. Имя
 * атрибута {@code shell} не пересекается с тем, что кладут контроллеры, —
 * экраны вроде главной по-прежнему сами решают, что показать в теле.
 *
 * Как и ссылки на главной, пункты шапки — удобство, а не права: настоящая
 * проверка стоит на методах сервисов, и прямой адрес отклоняется независимо
 * от того, нарисована ли ссылка на него.
 */
@ControllerAdvice
public class ShellAdvice {

    private final CurrentUser currentUser;
    private final Build build;

    public ShellAdvice(CurrentUser currentUser, ObjectProvider<BuildProperties> buildProperties,
                       ObjectProvider<GitProperties> gitProperties) {
        this.currentUser = currentUser;
        BuildProperties built = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        this.build = new Build(built == null ? null : built.getVersion(),
                git == null ? null : git.get("commit.id.abbrev"));
    }

    /**
     * Что показывает подвал: версия сборки и короткий хеш коммита (ADR-0043).
     * Любая часть может отсутствовать — сборка без метаданных или вне
     * git-каталога; подвал показывает то, что есть, и не падает.
     *
     * @param version версия из {@code pom.xml} либо {@code null}
     * @param commit  короткий хеш коммита либо {@code null}
     */
    public record Build(String version, String commit) {

        public Build {
            version = blankToNull(version);
            commit = blankToNull(commit);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * Версия и хеш для подвала. Отдельно от {@code shell}: у посетителя без
     * входа и на странице ошибки шапки нет, а подвал нужен именно им.
     */
    @ModelAttribute("build")
    public Build build() {
        return build;
    }

    /**
     * Что показывает шапка и что нужно страницам для возврата и раскрытия блоков.
     *
     * @param login         имя вошедшего
     * @param administrator показывать ли раздел учётных записей
     * @param teacher       показывать ли личный контур
     * @param section       открытый раздел — по нему подсвечивается пункт
     * @param back          проверенный адрес возврата из параметра {@code from}
     *                      либо {@code null}
     * @param here          адрес текущей страницы вместе с запросом — его несут
     *                      ссылки вглубь как {@code from}; {@code null} у отказа
     *                      на POST: адрес действия по ссылке не откроешь
     * @param posted        адрес действия, если страница показана в ответ на POST
     *                      (отказ сервиса), иначе {@code null}
     * @param params        параметры запроса при отказе на POST (пусто у GET):
     *                      экран с несколькими однотипными блоками раскрывает
     *                      тот, чьё действие отклонено, а не все сразу
     */
    public record Shell(String login, boolean administrator, boolean teacher, String section,
                        String back, String here, String posted,
                        java.util.Map<String, String> params) {

        /** Куда ведёт «назад»: на пришедший адрес, а без него — на раздел по умолчанию. */
        public String backOr(String fallback) {
            return back != null ? back : fallback;
        }

        /** Раскрывать ли блок: отклонённое действие — по этому адресу или под ним. */
        public boolean openedUnder(String prefix) {
            return posted != null && (posted.equals(prefix) || posted.startsWith(prefix + "/"));
        }

        /** Как {@link #opened}, но только для блока Задачи с этим идентификатором. */
        public boolean openedFor(Object problemId, String... suffixes) {
            return String.valueOf(problemId).equals(params.get("problem")) && opened(suffixes);
        }

        /**
         * Введённое значение для поля блока, чьё действие отклонено: человек
         * не набирает его заново. У прочих блоков и у страницы без отказа —
         * {@code null}, и поле остаётся своим обычным.
         */
        public String echo(String name, String... suffixes) {
            return opened(suffixes) ? params.get(name) : null;
        }

        /** Параметр отклонённого действия или {@code null}. */
        public String postedParam(String name) {
            return params.get(name);
        }

        /**
         * Раскрывать ли блок: страница показана в ответ на отклонённое действие,
         * чей адрес оканчивается одним из переданных окончаний. Контроллеры о
         * блоках не знают (ADR-0042).
         */
        public boolean opened(String... suffixes) {
            if (posted == null) {
                return false;
            }
            for (String suffix : suffixes) {
                if (posted.endsWith(suffix)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code null} для посетителя без входа: у формы входа шапки нет. */
    @ModelAttribute("shell")
    public Shell shell(HttpServletRequest request) {
        Optional<User> user = currentUser.loggedIn();
        return user.map(account -> new Shell(
                        account.login(),
                        account.hasRole(Role.ADMINISTRATOR),
                        account.hasRole(Role.TEACHER),
                        sectionOf(request),
                        ReturnTo.safe(parameter(request, "from")).orElse(null),
                        isPost(request) ? null : here(request),
                        isPost(request) ? request.getRequestURI() : null,
                        isPost(request) ? params(request) : java.util.Map.of()))
                .orElse(null);
    }

    /**
     * Параметры запроса для разбора отказа. У запроса с файлами — только
     * параметры адреса: тело разбирает контроллер, и разобрать его здесь
     * значило бы поднять отказ по размеру до обработчика (см. комментарий
     * к {@code resolve-lazily} в {@code application.yaml}).
     */
    private static java.util.Map<String, String> params(HttpServletRequest request) {
        java.util.Map<String, String> first = new java.util.HashMap<>();
        if (isMultipart(request)) {
            org.springframework.web.util.UriComponentsBuilder.newInstance()
                    .query(request.getQueryString()).build().getQueryParams()
                    .forEach((name, values) -> first.put(name, org.springframework.web.util.UriUtils
                            .decode(values.isEmpty() ? "" : values.get(0), java.nio.charset.StandardCharsets.UTF_8)));
            return first;
        }
        request.getParameterMap().forEach((name, values) -> {
            if (values.length > 0) {
                first.put(name, values[0]);
            }
        });
        return first;
    }

    private static String parameter(HttpServletRequest request, String name) {
        return isMultipart(request) ? params(request).get(name) : request.getParameter(name);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase().startsWith("multipart/");
    }

    private static boolean isPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    private static String here(HttpServletRequest request) {
        String query = request.getQueryString();
        String address = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
        return ReturnTo.safe(address).orElse(null);
    }

    private static String sectionOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals(Addresses.HOME)) {
            return "home";
        }
        if (path.startsWith(Addresses.TAXONOMY) || path.startsWith(Addresses.THEORY)) {
            return "taxonomy";
        }
        if (path.startsWith(Addresses.PROBLEMS)) {
            return "problems";
        }
        if (path.startsWith(Addresses.DICTIONARIES)) {
            return "dictionaries";
        }
        if (path.startsWith(Addresses.STUDENTS) || path.startsWith(Addresses.MASTERY)) {
            return "students";
        }
        if (path.startsWith(Addresses.WORKS)) {
            // Работы одного Ученика — часть его карточки, Работы Задания — часть Заданий.
            return request.getParameter("student") != null ? "students" : "assignments";
        }
        if (path.startsWith(Addresses.GROUPS)) {
            return "groups";
        }
        if (path.startsWith(Addresses.ASSIGNMENTS)) {
            return "assignments";
        }
        if (path.startsWith(Addresses.USERS)) {
            return "users";
        }
        return "";
    }
}
