package ru.locus.assistant;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;
import ru.locus.Addresses;
import ru.locus.upload.LargeUpload;

/**
 * Страница разговора с Помощником (design.md assistant-chat, Р1–Р4, Р7–Р11):
 * показанный Разговор с Репликами, форма запроса, «Остановить» и прежние
 * Разговоры. Видна обеим ролям; Разговоры — свои у каждого (ADR-0027).
 *
 * <p>После отправки и остановки — переадресация на адрес Разговора с якорем
 * Реплики: самообновление {@code meta refresh} перечитывает GET, а не
 * повторяет отправку. Отказ сервиса — та же страница в ответ на POST
 * с текстом отказа и набранным запросом.
 *
 * <p>Контроллер тонкий (standards.md, «Слои и границы»): какой Разговор
 * показать, решает {@link AssistantService}; здесь — только показ: время
 * в зоне часов приложения, итог Реплики строкой и HTML ответа
 * ({@link AnswerMarkdown}) с возвратом на показанный Разговор.
 */
@Controller
public class AssistantController {

    private static final String TURNS = Addresses.ASSISTANT + "/turns";

    /** Поля файла формы — {@code file1} … {@code file4}: номер файла — номер поля. */
    private static final int FILE_FIELDS = 4;

    private final AssistantService service;
    private final Clock clock;

    public AssistantController(AssistantService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * Реплика, как её показывает страница.
     *
     * @param startedAt время начала в зоне часов приложения
     * @param answer    HTML ответа ({@link AnswerMarkdown}) либо {@code null}, если ответа нет
     * @param total     «N токенов входа, M выхода · $…» либо {@code null}, если итога не было
     */
    public record TurnView(AssistantTurn turn, LocalTime startedAt, String answer, List<TurnStep> steps,
                           String total) {
    }

    /** Прежний Разговор в списке: когда говорили последний раз — в зоне часов приложения. */
    public record EarlierConversation(ConversationSummary summary, LocalDateTime lastTurnAt) {
    }

    @GetMapping(Addresses.ASSISTANT)
    public String page(@RequestParam(required = false) Long conversation,
                       @RequestParam(name = "new", defaultValue = "false") boolean fresh,
                       Model model) {
        return render(named(conversation), fresh, true, model);
    }

    /**
     * Начать Реплику в показанном Разговоре, а без него — в новом. Файлы
     * полей {@code file1} … {@code file4} принимает сервис через
     * {@link TurnAttachments}: номер файла — номер поля, пустое поле
     * пропускается, и номера остальных не сдвигаются (design.md
     * assistant-multiple-files, Р1). Предел загрузки — инструмента
     * черновиков, а не общий: PDF сборника весит десятки мегабайт
     * ({@link LargeUpload}). Запрос сверх него сюда не доходит — его
     * отклоняет {@link TooLargeTurnRefusal}.
     */
    @LargeUpload
    @PostMapping(TURNS)
    public String start(@RequestParam(required = false) Long conversation,
                        @RequestParam(defaultValue = "") String prompt,
                        HttpServletRequest request,
                        Model model) {
        Optional<AssistantConversationId> target = named(conversation);
        try {
            AssistantTurn turn = startWithFiles(target, prompt, files(request));
            return "redirect:" + turnAddress(turn);
        } catch (IllegalArgumentException | AssistantBusyException | AssistantUnavailableException refusal) {
            model.addAttribute("error", refusal.getMessage());
            model.addAttribute("prompt", prompt);
            return render(target, true, false, model);
        }
    }

    /** Остановить свою Реплику; чужая и несуществующая — 404. */
    @PostMapping(TURNS + "/{id}/stop")
    public String stop(@PathVariable long id) {
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        AssistantTurnId turn = new AssistantTurnId(id);
        service.stop(turn);
        return "redirect:" + turnAddress(service.turn(turn));
    }

    /** Непустые файлы полей формы по порядку полей; без файлов — пусто. */
    private static List<Numbered> files(HttpServletRequest request) {
        MultipartHttpServletRequest parts = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        List<Numbered> files = new ArrayList<>();
        if (parts == null) {
            return files;
        }
        for (int number = 1; number <= FILE_FIELDS; number++) {
            MultipartFile file = parts.getFile("file" + number);
            if (file != null && !file.isEmpty()) {
                files.add(new Numbered(number, file));
            }
        }
        return files;
    }

    private record Numbered(int number, MultipartFile file) {
    }

    /** Потоки открываются все до вызова сервиса и закрываются все, что бы ни случилось. */
    private AssistantTurn startWithFiles(Optional<AssistantConversationId> target, String prompt,
                                         List<Numbered> files) {
        List<InputStream> opened = new ArrayList<>();
        try {
            List<TurnAttachment> attachments = new ArrayList<>();
            for (Numbered numbered : files) {
                InputStream content = numbered.file().getInputStream();
                opened.add(content);
                attachments.add(new TurnAttachment(numbered.number(), numbered.file().getOriginalFilename(), content));
            }
            return service.startTurn(target, prompt, attachments);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            closeAll(opened);
        }
    }

    private static void closeAll(List<InputStream> opened) {
        for (InputStream content : opened) {
            try {
                content.close();
            } catch (IOException ignored) {
                // поток файла запроса: закрыть не вышло — убирать нечего
            }
        }
    }

    /**
     * @param get страница показана на GET: только она может перечитываться
     *            сама — {@code meta refresh} на ответе POST перечитал бы адрес
     *            действия
     */
    private String render(Optional<AssistantConversationId> named, boolean fresh, boolean get, Model model) {
        Optional<AssistantConversation> shown = service.conversation(named, fresh);
        Optional<AssistantTurn> running = service.runningTurn();
        String back = shown.map(conversation -> conversationAddress(conversation.id())).orElse(Addresses.ASSISTANT);
        boolean runningHere = running.isPresent() && shown.isPresent()
                && running.get().conversation().equals(shown.get().id());
        model.addAttribute("availability", service.availability());
        model.addAttribute("awaiting", service.awaitingReview().orElse(null));
        model.addAttribute("conversation", shown.orElse(null));
        model.addAttribute("turns", shown
                .map(conversation -> service.turns(conversation.id()).stream().map(turn -> view(turn, back)).toList())
                .orElse(List.of()));
        model.addAttribute("running", running.orElse(null));
        model.addAttribute("refresh", get && runningHere);
        model.addAttribute("elsewhere", running.isPresent() && !runningHere ? turnAddress(running.get()) : null);
        model.addAttribute("conversations", service.conversations().stream()
                .map(summary -> new EarlierConversation(summary,
                        LocalDateTime.ofInstant(summary.lastTurnAt(), clock.getZone())))
                .toList());
        return "assistant/page";
    }

    private TurnView view(AssistantTurn turn, String back) {
        return new TurnView(turn,
                LocalTime.ofInstant(turn.startedAt(), clock.getZone()),
                turn.answer() == null ? null : AnswerMarkdown.html(turn.answer(), back),
                service.steps(turn.id()),
                total(turn));
    }

    private static String total(AssistantTurn turn) {
        if (turn.inputTokens() == null || turn.outputTokens() == null) {
            return null;
        }
        String tokens = turn.inputTokens() + " токенов входа, " + turn.outputTokens() + " выхода";
        return turn.costUsd() == null
                ? tokens
                : tokens + " · $" + turn.costUsd().setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** Названный Разговор; не положительный номер — как несуществующий. */
    private static Optional<AssistantConversationId> named(Long conversation) {
        if (conversation == null) {
            return Optional.empty();
        }
        if (conversation <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Optional.of(new AssistantConversationId(conversation));
    }

    private static String conversationAddress(AssistantConversationId conversation) {
        return Addresses.ASSISTANT + "?conversation=" + conversation.value();
    }

    private static String turnAddress(AssistantTurn turn) {
        return conversationAddress(turn.conversation()) + "#turn-" + turn.id().value();
    }
}
