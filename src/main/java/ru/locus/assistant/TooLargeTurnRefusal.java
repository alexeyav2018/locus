package ru.locus.assistant;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import ru.locus.Addresses;

/**
 * Отказ по пределу загрузки на странице Помощника, в том же Разговоре
 * (design.md assistant-multiple-files, Р4).
 *
 * <p>До контроллера такой запрос не доходит: тело с файлами разбирает
 * контейнер ещё в фильтре CSRF, ищущем токен формы, и сам отвечает 413.
 * Поэтому отказ ловится здесь — на странице ошибки: запрос к адресу
 * действия формы Помощника переадресуется на его Разговор, а отказ
 * передаётся flash-атрибутом {@code error} — тем, что показывает отказ
 * сервиса. Тело не разобрано, и набранный текст пропал: страница говорит
 * об этом. Разговор берётся из адреса действия, куда его кладёт форма.
 *
 * <p>Пределы — из {@link MultipartProperties}, чтобы не разойтись
 * с {@code application.yaml}.
 */
@Component
public class TooLargeTurnRefusal implements ErrorViewResolver, Ordered {

    private static final String TURNS = Addresses.ASSISTANT + "/turns";

    private final MultipartProperties multipart;

    public TooLargeTurnRefusal(MultipartProperties multipart) {
        this.multipart = multipart;
    }

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (status.value() != HttpStatus.CONTENT_TOO_LARGE.value() || !(request.getContextPath() + TURNS).equals(uri)) {
            return null;
        }
        RequestContextUtils.getOutputFlashMap(request).put("error", "Файлы слишком велики: до "
                + size(multipart.getMaxFileSize()) + " на файл и до " + size(multipart.getMaxRequestSize())
                + " за один запрос. Набранный текст не сохранился — наберите его снова.");
        RedirectView back = new RedirectView(conversation(request), true);
        back.setExposeModelAttributes(false);
        return new ModelAndView(back);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Адрес Разговора из строки запроса, а не {@code getParameter}: тот
     * на запросе с файлами снова полез бы в тело, разбор которого и упал.
     * Не номер — страница без Разговора; чужой Разговор отклонит сама
     * страница.
     */
    private static String conversation(HttpServletRequest request) {
        String value = UriComponentsBuilder.newInstance().query(request.getQueryString()).build()
                .getQueryParams().getFirst("conversation");
        try {
            return value == null ? Addresses.ASSISTANT
                    : Addresses.ASSISTANT + "?conversation=" + Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Addresses.ASSISTANT;
        }
    }

    /** «100 МБ», «2 КБ», «512 байт» — как предел записан в настройках. */
    private static String size(DataSize size) {
        long bytes = size.toBytes();
        if (bytes % DataSize.ofMegabytes(1).toBytes() == 0) {
            return size.toMegabytes() + " МБ";
        }
        if (bytes % DataSize.ofKilobytes(1).toBytes() == 0) {
            return size.toKilobytes() + " КБ";
        }
        return bytes + " байт";
    }
}
