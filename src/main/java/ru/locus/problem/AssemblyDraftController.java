package ru.locus.problem;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.locus.Addresses;
import ru.locus.upload.LargeUpload;

/**
 * Загрузка исходника для сборки PDF слота Задачи (ADR-0044).
 *
 * Отвечает не страницей, а фрагментом — строкой сборки
 * {@code problem/assembly :: row}, которую общий {@code locus.js} вставляет в список
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

    /**
     * Страница своего черновика картинкой JPEG — по ней скрипт формы рисует
     * рамку (ADR-0045). Чужой, несуществующий черновик и страница вне его —
     * один ответ 404.
     *
     * Содержимое черновика неизменно, поэтому браузер держит показанную
     * страницу час; {@code private} не даёт положить её в общий кэш.
     */
    @GetMapping(Addresses.PROBLEMS + "/drafts/{id}/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable long id, @PathVariable int page) {
        if (id <= 0) {
            return ResponseEntity.notFound().build();
        }
        return drafts.preview(new AssemblyDraftId(id), page)
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .body(content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
