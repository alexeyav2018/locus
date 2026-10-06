package ru.locus.problem;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.locus.Addresses;
import ru.locus.upload.LargeUpload;

/**
 * Загрузка исходника для сборки PDF слота Задачи (ADR-0041).
 *
 * Отвечает не страницей, а фрагментом — строкой сборки
 * {@code problem/assembly :: row}, которую скрипт формы вставляет в список
 * слота. Та же разметка рисует строки при перерисовке формы после отказа,
 * поэтому строка у загрузки и у отказа одна.
 *
 * Контроллер тонкий: права и распознавание исходника — в
 * {@link AssemblyDraftService#upload}, здесь только разбор запроса.
 */
@Controller
public class AssemblyDraftController {

    /** Слоты Задачи: имя слота становится префиксом полей строки. */
    private static final Set<String> SLOTS = Set.of("condition", "solution");

    private final AssemblyDraftService drafts;

    public AssemblyDraftController(AssemblyDraftService drafts) {
        this.drafts = drafts;
    }

    /**
     * Принимает исходник и отдаёт его строку сборки со всеми страницами.
     * Неподходящий исходник — тот же фрагмент-отказ с кодом 422: скрипт
     * показывает текст у слота, форма при этом не теряется.
     *
     * Предел загрузки — инструмента, а не общий: сборник весит десятки
     * мегабайт ({@link LargeUpload}).
     */
    @LargeUpload
    @PostMapping(Addresses.PROBLEMS + "/drafts")
    public String upload(@RequestParam String slot,
                         @RequestParam MultipartFile source,
                         Model model,
                         HttpServletResponse response) {
        if (!SLOTS.contains(slot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Слота «" + slot + "» у Задачи нет");
        }
        try (InputStream content = source.getInputStream()) {
            AssemblyDraft draft = drafts.upload(source.getOriginalFilename(), content);
            model.addAttribute("slot", slot);
            model.addAttribute("row", AssemblyRow.whole(draft));
            return "problem/assembly :: row(slot=${slot}, row=${row})";
        } catch (IllegalArgumentException refusal) {
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            model.addAttribute("message", refusal.getMessage());
            return "problem/assembly :: refusal(message=${message})";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
