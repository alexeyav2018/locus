package ru.locus.problem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.locus.Addresses;
import ru.locus.file.FileKey;
import ru.locus.file.FilePages;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.CharacteristicService;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.dictionary.SolutionMethodService;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.taxonomy.TaxonomyService;
import ru.locus.user.CurrentUser;
import ru.locus.user.Role;

/**
 * Страницы Задачи: заведение, правка и просмотр.
 *
 * Список Задач Темы живёт не здесь, а в правой части экрана дерева
 * ({@code taxonomy/tree.html}): «выбрать Тему» — единственная навигация,
 * которая у библиотеки сегодня есть, и второе дерево на отдельном экране
 * разъехалось бы с первым сначала оформлением, потом поведением
 * (design.md, «Экран»).
 *
 * Контроллер тонкий: разбор запроса и выбор шаблона, ни предметной логики,
 * ни проверок прав, ни обращений к репозиторию (standards.md, «Слои
 * и границы»). Права проверяет {@link ProblemService} на каждой операции,
 * поэтому отказ происходит одинаково и при нажатии кнопки, и при обращении
 * по прямому адресу.
 *
 * Роль здесь читается ровно для одного — показывать ли формы правки.
 * Правами это не является: разметка ничего не разрешает и не запрещает.
 */
@Controller
public class ProblemController {

    private final ProblemService problems;
    private final TaxonomyService taxonomy;
    private final SolutionMethodService methods;
    private final CharacteristicService characteristics;
    private final CurrentUser currentUser;
    private final AssemblyDraftService drafts;
    private final FilePages filePages;

    public ProblemController(ProblemService problems,
                             TaxonomyService taxonomy,
                             SolutionMethodService methods,
                             CharacteristicService characteristics,
                             CurrentUser currentUser,
                             AssemblyDraftService drafts,
                             FilePages filePages) {
        this.problems = problems;
        this.taxonomy = taxonomy;
        this.methods = methods;
        this.characteristics = characteristics;
        this.currentUser = currentUser;
        this.drafts = drafts;
        this.filePages = filePages;
    }

    /**
     * Форма заведения. Тема, с которой пришли, выбрана заранее: заводят Задачу
     * с экрана дерева, стоя на Теме, и заставлять выбирать её второй раз —
     * приглашение ошибиться.
     *
     * Показ формы — открытие инструмента сборки, и с него начинается уборка
     * брошенных черновиков (ADR-0044).
     */
    @GetMapping(Addresses.PROBLEMS + "/new")
    public String form(@RequestParam(required = false) Long topic, Model model) {
        drafts.sweep();
        fillMarkup(ProblemForm.startingAt(topic), model);
        return "problem/form";
    }

    /**
     * Заводит Задачу. Вид выбран в форме явно (design.md изменения
     * {@code problem-text}, Р2, Р4): у Задачи с текстом поля слотов дают
     * Улики, у Задачи без текста — сами PDF. Запрос без вида — Задача без
     * текста, как до появления текста: так его шлёт прежняя форма.
     */
    @PostMapping(Addresses.PROBLEMS)
    public String create(@RequestParam(required = false) ExamPart part,
                         @RequestParam(required = false) List<Long> topics,
                         @RequestParam(required = false) List<Long> methodIds,
                         @RequestParam(required = false) List<Long> characteristicIds,
                         @RequestParam(required = false) ProblemForm.Kind kind,
                         @RequestParam(required = false) String conditionText,
                         @RequestParam(required = false) String solutionText,
                         @RequestParam(required = false) MultipartFile condition,
                         @RequestParam(required = false) MultipartFile solution,
                         @RequestParam(required = false) List<Long> conditionDraft,
                         @RequestParam(required = false) List<Integer> conditionFrom,
                         @RequestParam(required = false) List<Integer> conditionTo,
                         @RequestParam(required = false) List<String> conditionCrop,
                         @RequestParam(required = false) List<Long> solutionDraft,
                         @RequestParam(required = false) List<Integer> solutionFrom,
                         @RequestParam(required = false) List<Integer> solutionTo,
                         @RequestParam(required = false) List<String> solutionCrop,
                         Model model) {
        PdfAssemblyOrder conditionOrder =
                AssemblyOrderForm.order(conditionDraft, conditionFrom, conditionTo, conditionCrop);
        PdfAssemblyOrder solutionOrder =
                AssemblyOrderForm.order(solutionDraft, solutionFrom, solutionTo, solutionCrop);
        ProblemForm.Kind chosen = kind == null ? ProblemForm.Kind.FILES : kind;
        try {
            AssemblyOrderForm.requireFrames(conditionCrop);
            AssemblyOrderForm.requireFrames(solutionCrop);
            ProblemPdf conditionSlot = slot(condition, conditionOrder, "условия");
            ProblemPdf solutionSlot = slot(solution, solutionOrder, "решения");
            ProblemId created = chosen == ProblemForm.Kind.TEXT
                    ? problems.create(part, nodeIds(topics), methodIds(methodIds),
                            characteristicIds(characteristicIds), conditionText, solutionText,
                            conditionSlot, solutionSlot)
                    : problems.create(part, nodeIds(topics), methodIds(methodIds),
                            characteristicIds(characteristicIds), conditionSlot, solutionSlot);
            return "redirect:" + Addresses.PROBLEMS + "/" + created.value();
        } catch (NotATopicException | IllegalArgumentException refusal) {
            AssemblyRefusals.report(refusal);
            String page = sentForm(ProblemForm.sent(part, topics, methodIds, characteristicIds,
                    chosen, conditionText, solutionText), conditionOrder, solutionOrder, model);
            model.addAttribute("error", refusal.getMessage());
            return page;
        }
    }

    /**
     * Предпросмотр заведения (Р9): кнопка той же формы с {@code formaction},
     * поэтому приходит всё, что в форме введено, и всё это возвращается
     * в форму — тексты, разметка, строки сборки. Отрисовка — в памяти:
     * ни Задачи, ни файлов. Готовый файл, выбранный в поле без скрипта,
     * браузер в повторно показанную форму не вернёт — об этом подсказка
     * у кнопки; черновики сборки живут сами и переживают предпросмотр.
     */
    @PostMapping(Addresses.PROBLEMS + "/preview")
    public String preview(@RequestParam(required = false) ExamPart part,
                          @RequestParam(required = false) List<Long> topics,
                          @RequestParam(required = false) List<Long> methodIds,
                          @RequestParam(required = false) List<Long> characteristicIds,
                          @RequestParam(required = false) String conditionText,
                          @RequestParam(required = false) String solutionText,
                          @RequestParam(required = false) List<Long> conditionDraft,
                          @RequestParam(required = false) List<Integer> conditionFrom,
                          @RequestParam(required = false) List<Integer> conditionTo,
                          @RequestParam(required = false) List<String> conditionCrop,
                          @RequestParam(required = false) List<Long> solutionDraft,
                          @RequestParam(required = false) List<Integer> solutionFrom,
                          @RequestParam(required = false) List<Integer> solutionTo,
                          @RequestParam(required = false) List<String> solutionCrop,
                          Model model) {
        String page = sentForm(ProblemForm.sent(part, topics, methodIds, characteristicIds,
                        ProblemForm.Kind.TEXT, conditionText, solutionText),
                AssemblyOrderForm.order(conditionDraft, conditionFrom, conditionTo, conditionCrop),
                AssemblyOrderForm.order(solutionDraft, solutionFrom, solutionTo, solutionCrop), model);
        model.addAttribute("preview", preview(conditionText, solutionText));
        return page;
    }

    private String sentForm(ProblemForm form, PdfAssemblyOrder conditionOrder, PdfAssemblyOrder solutionOrder,
                            Model model) {
        fillMarkup(form, model);
        model.addAttribute("conditionRows", drafts.rows(conditionOrder));
        model.addAttribute("solutionRows", drafts.rows(solutionOrder));
        return "problem/form";
    }

    /** Отрисовка в памяти или её отказ — на месте предпросмотра, а не наверху страницы. */
    private TextPreview preview(String conditionText, String solutionText) {
        try {
            return TextPreview.of(problems.preview(conditionText, solutionText));
        } catch (IllegalArgumentException refusal) {
            return TextPreview.refused(refusal.getMessage());
        }
    }

    /** Просмотр Задачи: разметка, номер и обе временные ссылки. */
    @GetMapping(Addresses.PROBLEMS + "/{id}")
    public String problem(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("problem", problem);
        model.addAttribute("topicNames", taxonomy.paths().stream()
                .filter(path -> problem.topics().contains(path.id()))
                .map(path -> path.path())
                .toList());
        model.addAttribute("methodNames", methods.all().stream()
                .filter(method -> problem.methods().contains(method.id()))
                .map(method -> method.name())
                .toList());
        model.addAttribute("characteristicNames", characteristics.all().stream()
                .filter(characteristic -> problem.characteristics().contains(characteristic.id()))
                .map(characteristic -> characteristic.name())
                .toList());
        model.addAttribute("conditionLink", problems.conditionLink(problem.id()).toString());
        model.addAttribute("solutionLink", problems.solutionLink(problem.id()).toString());
        model.addAttribute("administrator", currentUser.account().hasRole(Role.ADMINISTRATOR));
        return "problem/problem";
    }

    /** Просмотр PDF условия внутри системы: страница с кнопкой «Назад» вместо чужой вкладки. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/condition")
    public String viewCondition(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("view", filePages.view("Условие — Задача № " + problem.number(),
                problems.conditionLink(problem.id()).toString(), problem.conditionFile(),
                Addresses.PROBLEMS + "/" + id + "/condition", Addresses.PROBLEMS + "/" + id));
        return "file/viewer";
    }

    /** Картинка страницы условия для страницы просмотра (ADR-0052). */
    @GetMapping(Addresses.PROBLEMS + "/{id}/condition/pages/{page}")
    public ResponseEntity<byte[]> conditionPage(@PathVariable long id, @PathVariable int page, WebRequest request) {
        return filePages.image(problems.problem(new ProblemId(id)).conditionFile(), page, request);
    }

    /** Просмотр PDF решения внутри системы. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/solution")
    public String viewSolution(@PathVariable long id, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        model.addAttribute("view", filePages.view("Решение — Задача № " + problem.number(),
                problems.solutionLink(problem.id()).toString(), problem.solutionFile(),
                Addresses.PROBLEMS + "/" + id + "/solution", Addresses.PROBLEMS + "/" + id));
        return "file/viewer";
    }

    /** Картинка страницы решения для страницы просмотра. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/solution/pages/{page}")
    public ResponseEntity<byte[]> solutionPage(@PathVariable long id, @PathVariable int page, WebRequest request) {
        return filePages.image(problems.problem(new ProblemId(id)).solutionFile(), page, request);
    }

    /** Форма правки — та же, что и заведения, но с заполненной Задачей. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        drafts.sweep();
        Problem problem = problems.problem(new ProblemId(id));
        return editForm(problem, ProblemForm.of(problem), model);
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}")
    public String edit(@PathVariable long id,
                       @RequestParam(required = false) ExamPart part,
                       @RequestParam(required = false) List<Long> topics,
                       @RequestParam(required = false) List<Long> methodIds,
                       @RequestParam(required = false) List<Long> characteristicIds,
                       Model model) {
        try {
            problems.edit(new ProblemId(id), part,
                    nodeIds(topics), methodIds(methodIds), characteristicIds(characteristicIds));
        } catch (ProblemInUseException | NotATopicException | IllegalArgumentException refusal) {
            Problem problem = problems.problem(new ProblemId(id));
            String page = editForm(problem, ProblemForm.sent(part, topics, methodIds, characteristicIds),
                    model);
            model.addAttribute("error", refusal.getMessage());
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}/condition")
    public String replaceCondition(@PathVariable long id,
                                   @RequestParam(required = false) MultipartFile condition,
                                   @RequestParam(required = false) List<Long> conditionDraft,
                                   @RequestParam(required = false) List<Integer> conditionFrom,
                                   @RequestParam(required = false) List<Integer> conditionTo,
                                   @RequestParam(required = false) List<String> conditionCrop,
                                   Model model) {
        PdfAssemblyOrder order =
                AssemblyOrderForm.order(conditionDraft, conditionFrom, conditionTo, conditionCrop);
        try {
            AssemblyOrderForm.requireFrames(conditionCrop);
            problems.replaceCondition(new ProblemId(id), slot(condition, order, "условия"));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            String page = refusedEdit(id, refusal, model);
            model.addAttribute("conditionRows", drafts.rows(order));
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    @PostMapping(Addresses.PROBLEMS + "/{id}/solution")
    public String replaceSolution(@PathVariable long id,
                                  @RequestParam(required = false) MultipartFile solution,
                                  @RequestParam(required = false) List<Long> solutionDraft,
                                  @RequestParam(required = false) List<Integer> solutionFrom,
                                  @RequestParam(required = false) List<Integer> solutionTo,
                                  @RequestParam(required = false) List<String> solutionCrop,
                                  Model model) {
        PdfAssemblyOrder order =
                AssemblyOrderForm.order(solutionDraft, solutionFrom, solutionTo, solutionCrop);
        try {
            AssemblyOrderForm.requireFrames(solutionCrop);
            problems.replaceSolution(new ProblemId(id), slot(solution, order, "решения"));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            String page = refusedEdit(id, refusal, model);
            model.addAttribute("solutionRows", drafts.rows(order));
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    /**
     * Правка текста условия (Р7): версия — та, с которой открыта страница.
     * Перерисовывается только этот слот.
     */
    @PostMapping(Addresses.PROBLEMS + "/{id}/condition/text")
    public String editConditionText(@PathVariable long id,
                                    @RequestParam(required = false) String conditionText,
                                    @RequestParam(defaultValue = "0") int version,
                                    Model model) {
        return editText(id, ProblemSlot.CONDITION, conditionText, version, model);
    }

    /** Правка текста решения — тем же правилом. */
    @PostMapping(Addresses.PROBLEMS + "/{id}/solution/text")
    public String editSolutionText(@PathVariable long id,
                                   @RequestParam(required = false) String solutionText,
                                   @RequestParam(defaultValue = "0") int version,
                                   Model model) {
        return editText(id, ProblemSlot.SOLUTION, solutionText, version, model);
    }

    private String editText(long id, ProblemSlot slot, String text, int version, Model model) {
        try {
            problems.editText(new ProblemId(id), slot, text, version);
        } catch (ProblemInUseException | ProblemTextChangedException | IllegalArgumentException refusal) {
            return refusedText(id, slot == ProblemSlot.CONDITION ? text : null,
                    slot == ProblemSlot.SOLUTION ? text : null, version, refusal, model);
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    /**
     * Записать текст Задаче без текста (Р5): оба текста сразу, прежние PDF
     * становятся Уликами своих слотов.
     */
    @PostMapping(Addresses.PROBLEMS + "/{id}/text")
    public String writeText(@PathVariable long id,
                            @RequestParam(required = false) String conditionText,
                            @RequestParam(required = false) String solutionText,
                            Model model) {
        try {
            problems.writeText(new ProblemId(id), conditionText, solutionText);
        } catch (ProblemInUseException | ProblemTextChangedException | IllegalArgumentException refusal) {
            return refusedText(id, conditionText, solutionText, 0, refusal, model);
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    /**
     * Предпросмотр на странице правки (Р9) — и для правки слота, и для
     * «Записать текст»: приходит тот текст, что был в отправленной форме,
     * и только он отрисовывается. Версия возвращается в форму той же,
     * с какой страница открыта: предпросмотр — не повод забыть, что текст
     * могли изменить.
     */
    @PostMapping(Addresses.PROBLEMS + "/{id}/text/preview")
    public String previewText(@PathVariable long id,
                              @RequestParam(required = false) String conditionText,
                              @RequestParam(required = false) String solutionText,
                              @RequestParam(defaultValue = "0") int version,
                              Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        String page = editForm(problem, ProblemForm.of(problem).withTexts(conditionText, solutionText, version),
                model);
        model.addAttribute("preview", preview(conditionText, solutionText));
        return page;
    }

    /**
     * Текст условия файлом {@code .md} (Р8): ровно как хранится, в UTF-8,
     * с именем, называющим номер и слот. Читают все вошедшие; заморозка
     * скачиванию не мешает. У Задачи без текста скачивать нечего — 404.
     */
    @GetMapping(Addresses.PROBLEMS + "/{id}/condition.md")
    public ResponseEntity<byte[]> conditionMarkdown(@PathVariable long id) {
        return markdown(id, ProblemSlot.CONDITION);
    }

    /** Текст решения файлом {@code .md}. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/solution.md")
    public ResponseEntity<byte[]> solutionMarkdown(@PathVariable long id) {
        return markdown(id, ProblemSlot.SOLUTION);
    }

    /**
     * Имя файла называет обработчик сам: без своего {@code Content-Disposition}
     * браузер назвал бы файл по адресу (antipatterns.md, «Отдача файла без
     * своего Content-Disposition»).
     */
    private ResponseEntity<byte[]> markdown(long id, ProblemSlot slot) {
        Problem problem = problems.problem(new ProblemId(id));
        if (!problem.hasText()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Задача " + problem.number() + " — " + slot.title() + ".md", StandardCharsets.UTF_8)
                        .build().toString())
                .body(problem.text().of(slot).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Загрузка поправленного {@code .md} условия: заменяет текст своего слота
     * по правилам правки в форме — с версией, пределом и перерисовкой только
     * этого слота.
     */
    @PostMapping(Addresses.PROBLEMS + "/{id}/condition.md")
    public String uploadConditionMarkdown(@PathVariable long id,
                                          @RequestParam(required = false) MultipartFile file,
                                          @RequestParam(defaultValue = "0") int version,
                                          Model model) {
        return uploadMarkdown(id, ProblemSlot.CONDITION, file, version, model);
    }

    /** Загрузка поправленного {@code .md} решения. */
    @PostMapping(Addresses.PROBLEMS + "/{id}/solution.md")
    public String uploadSolutionMarkdown(@PathVariable long id,
                                         @RequestParam(required = false) MultipartFile file,
                                         @RequestParam(defaultValue = "0") int version,
                                         Model model) {
        return uploadMarkdown(id, ProblemSlot.SOLUTION, file, version, model);
    }

    private String uploadMarkdown(long id, ProblemSlot slot, MultipartFile file, int version, Model model) {
        try {
            problems.editText(new ProblemId(id), slot, markdownOf(file), version);
        } catch (ProblemInUseException | ProblemTextChangedException | IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id;
    }

    /**
     * Содержимое присланного {@code .md}: строго UTF-8, метка порядка байтов
     * снимается — её ставят редакторы Windows, а в тексте Задачи она была бы
     * невидимым знаком перед первым словом. Файл в другой кодировке
     * не перекодируется наугад, а отклоняется.
     */
    private static String markdownOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Не выбран файл .md");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes()))
                    .toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            throw new IllegalArgumentException("Файл .md не в кодировке UTF-8 — сохраните его в UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Улика условия на странице просмотра (Р4): те же правила доступа, что у PDF Задачи. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/condition/evidence")
    public String viewConditionEvidence(@PathVariable long id, Model model) {
        return viewEvidence(id, ProblemSlot.CONDITION, model);
    }

    /** Картинка страницы Улики условия. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/condition/evidence/pages/{page}")
    public ResponseEntity<byte[]> conditionEvidencePage(@PathVariable long id, @PathVariable int page,
                                                        WebRequest request) {
        return filePages.image(evidenceOf(id, ProblemSlot.CONDITION), page, request);
    }

    /** Улика решения на странице просмотра. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/solution/evidence")
    public String viewSolutionEvidence(@PathVariable long id, Model model) {
        return viewEvidence(id, ProblemSlot.SOLUTION, model);
    }

    /** Картинка страницы Улики решения. */
    @GetMapping(Addresses.PROBLEMS + "/{id}/solution/evidence/pages/{page}")
    public ResponseEntity<byte[]> solutionEvidencePage(@PathVariable long id, @PathVariable int page,
                                                       WebRequest request) {
        return filePages.image(evidenceOf(id, ProblemSlot.SOLUTION), page, request);
    }

    private String viewEvidence(long id, ProblemSlot slot, Model model) {
        FileKey evidence = evidenceOf(id, slot);
        String viewer = Addresses.PROBLEMS + "/" + id + "/" + slotPath(slot) + "/evidence";
        model.addAttribute("view", filePages.view(
                "Улика: " + slot.title() + " — Задача № " + id,
                problems.evidenceLink(new ProblemId(id), slot).toString(), evidence,
                viewer, Addresses.PROBLEMS + "/" + id + "/edit"));
        return "file/viewer";
    }

    /** Ключ Улики слота; нет текста или Улики — 404, как у несуществующего файла. */
    private FileKey evidenceOf(long id, ProblemSlot slot) {
        Problem problem = problems.problem(new ProblemId(id));
        if (!problem.hasText() || problem.text().evidenceOf(slot) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return problem.text().evidenceOf(slot);
    }

    /** Замена Улики условия — готовым PDF или сборкой, как замена PDF у Задачи без текста. */
    @PostMapping(Addresses.PROBLEMS + "/{id}/condition/evidence")
    public String replaceConditionEvidence(@PathVariable long id,
                                           @RequestParam(required = false) MultipartFile condition,
                                           @RequestParam(required = false) List<Long> conditionDraft,
                                           @RequestParam(required = false) List<Integer> conditionFrom,
                                           @RequestParam(required = false) List<Integer> conditionTo,
                                           @RequestParam(required = false) List<String> conditionCrop,
                                           Model model) {
        PdfAssemblyOrder order =
                AssemblyOrderForm.order(conditionDraft, conditionFrom, conditionTo, conditionCrop);
        try {
            AssemblyOrderForm.requireFrames(conditionCrop);
            problems.replaceEvidence(new ProblemId(id), ProblemSlot.CONDITION, slot(condition, order, "условия"));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            String page = refusedEdit(id, refusal, model);
            model.addAttribute("conditionRows", drafts.rows(order));
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id + "/edit";
    }

    /** Замена Улики решения. */
    @PostMapping(Addresses.PROBLEMS + "/{id}/solution/evidence")
    public String replaceSolutionEvidence(@PathVariable long id,
                                          @RequestParam(required = false) MultipartFile solution,
                                          @RequestParam(required = false) List<Long> solutionDraft,
                                          @RequestParam(required = false) List<Integer> solutionFrom,
                                          @RequestParam(required = false) List<Integer> solutionTo,
                                          @RequestParam(required = false) List<String> solutionCrop,
                                          Model model) {
        PdfAssemblyOrder order =
                AssemblyOrderForm.order(solutionDraft, solutionFrom, solutionTo, solutionCrop);
        try {
            AssemblyOrderForm.requireFrames(solutionCrop);
            problems.replaceEvidence(new ProblemId(id), ProblemSlot.SOLUTION, slot(solution, order, "решения"));
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            String page = refusedEdit(id, refusal, model);
            model.addAttribute("solutionRows", drafts.rows(order));
            return page;
        }
        return "redirect:" + Addresses.PROBLEMS + "/" + id + "/edit";
    }

    private static String slotPath(ProblemSlot slot) {
        return slot == ProblemSlot.CONDITION ? "condition" : "solution";
    }

    /**
     * Снимает Задачу и возвращает на Тему, где она лежала: самой Задачи больше
     * нет, а место, где работал Администратор, теряться не должно.
     */
    @PostMapping(Addresses.PROBLEMS + "/{id}/deletion")
    public String delete(@PathVariable long id, Model model) {
        try {
            Problem problem = problems.problem(new ProblemId(id));
            long topic = problem.topics().get(0).value();
            problems.delete(problem.id());
            return "redirect:" + Addresses.TAXONOMY + "?node=" + topic + "#node-" + topic;
        } catch (ProblemInUseException | IllegalArgumentException refusal) {
            return refusedEdit(id, refusal, model);
        }
    }

    private String editForm(Problem problem, ProblemForm form, Model model) {
        model.addAttribute("problem", problem);
        fillMarkup(form, model);
        return "problem/form";
    }

    /**
     * Что отмечено в форме разметки и что в ней предлагается: Темы дерева,
     * Методы и Характеристики.
     *
     * Методы Темы идут отдельным списком и показываются первыми, полный
     * словарь — вторым: на сотне записей выбор из полного списка превращается
     * в перебор, при котором заводится смысловой дубль вместо существующей
     * записи (ADR-0009, ADR-0010). Выбор при этом ничем не ограничен — Метод,
     * в Теме не встречавшийся, указать можно. Подсказка строится по первой
     * отмеченной Теме — после отказа это первая присланная.
     */
    private void fillMarkup(ProblemForm form, Model model) {
        Long topic = form.firstTopic();
        model.addAttribute("form", form);
        model.addAttribute("topicPaths", taxonomy.topicPaths());
        model.addAttribute("topicMethods",
                topic == null ? List.of() : problems.methodsUsedIn(new TaxonomyNodeId(topic)));
        model.addAttribute("methods", methods.all());
        model.addAttribute("characteristics", characteristics.all());
        model.addAttribute("parts", ExamPart.values());
    }

    /**
     * Отказ замены файла или удаления: форма с сохранённой Задачей. Разметку
     * эти формы не присылают, поэтому показывать, кроме сохранённой, нечего.
     */
    private String refusedEdit(long id, RuntimeException refusal, Model model) {
        AssemblyRefusals.report(refusal);
        String message = refusal.getMessage();
        String page = edit(id, model);
        model.addAttribute("error", message);
        return page;
    }

    /**
     * Отказ правки текста: форма с присланным текстом и версией — человек
     * не набирает текст заново, а версия остаётся прочитанной, чтобы
     * отказ «перечитайте» не исчез на втором нажатии.
     */
    private String refusedText(long id, String conditionText, String solutionText, int version,
                               RuntimeException refusal, Model model) {
        Problem problem = problems.problem(new ProblemId(id));
        String page = editForm(problem, ProblemForm.of(problem).withTexts(conditionText, solutionText, version),
                model);
        model.addAttribute("error", refusal.getMessage());
        return page;
    }

    /**
     * Присланный файл к виду, понятному сервису. Пустое поле — это отсутствие
     * файла, а не пустой файл: отказ о недостающем PDF должен звучать
     * одинаково и когда поле не заполнено, и когда его вовсе нет в запросе.
     */
    private static UploadedFile uploaded(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new UploadedFile(file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Слот из формы: готовый файл или порядок сборки, но не оба сразу.
     *
     * Выбор одного из двух — разбор формы, а не правило Задачи: сервис
     * получает ровно один вариант, и что он был единственным, решается здесь
     * (design.md, «Слот принимает „готовый файл или порядок сборки“»).
     * Поле файла у слота одно (ADR-0049): готовый файл приходит из него, только
     * когда скрипт не работал, — со скриптом он снимает у поля имя, и приходят
     * строки сборки.
     */
    private static ProblemPdf slot(MultipartFile file, PdfAssemblyOrder order, String what) {
        UploadedFile ready = uploaded(file);
        if (ready != null && !order.isEmpty()) {
            throw new IllegalArgumentException("PDF " + what
                    + ": приложен готовый файл и задана сборка — выберите один способ");
        }
        return ready != null ? ready : order.isEmpty() ? null : order;
    }

    private static List<TaxonomyNodeId> nodeIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(TaxonomyNodeId::new).toList();
    }

    private static List<SolutionMethodId> methodIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(SolutionMethodId::new).toList();
    }

    private static List<CharacteristicId> characteristicIds(List<Long> values) {
        return values == null ? List.of() : values.stream().map(CharacteristicId::new).toList();
    }
}
