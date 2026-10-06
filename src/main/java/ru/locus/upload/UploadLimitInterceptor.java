package ru.locus.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

/**
 * Держит общий предел загрузки на всех обработчиках, кроме отмеченных
 * {@link LargeUpload} (ADR-0041).
 *
 * Превышение сообщается тем же {@link MaxUploadSizeExceededException},
 * каким его сообщал бы контейнер. Перехватчик работает после выбора
 * обработчика, поэтому исключение попадает в {@code @ExceptionHandler}
 * контроллера — экран приёма Работ показывает отказ своим текстом,
 * как и прежде, а обработчики без своего отказа отвечают 413.
 *
 * Тело разбирается здесь же, при первом обращении к файлам
 * ({@code resolve-lazily}): запрос сверх предела контейнера роняет разбор
 * тем же исключением.
 */
public class UploadLimitInterceptor implements HandlerInterceptor {

    private final UploadLimitProperties limits;

    public UploadLimitInterceptor(UploadLimitProperties limits) {
        this.limits = limits;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(LargeUpload.class)) {
            return true;
        }
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart == null) {
            return true;
        }
        long maxRequest = limits.maxRequestSize().toBytes();
        if (request.getContentLengthLong() > maxRequest) {
            throw new MaxUploadSizeExceededException(maxRequest);
        }
        long maxFile = limits.maxFileSize().toBytes();
        long total = 0;
        for (List<MultipartFile> files : multipart.getMultiFileMap().values()) {
            for (MultipartFile file : files) {
                if (file.getSize() > maxFile) {
                    throw new MaxUploadSizeExceededException(maxFile);
                }
                total += file.getSize();
            }
        }
        if (total > maxRequest) {
            throw new MaxUploadSizeExceededException(maxRequest);
        }
        return true;
    }
}
